package com.callstream.recorder.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Offline two-pass loudness normalizer for recorded calls.
 *
 * The problem this solves: in a mic-captured call the near end (you) is loud and the
 * far end (speaker → mic) is quiet. A naive real-time AGC reacts to the instantaneous
 * buffer level, so it "pumps" — slamming gain up and down and amplifying silence into
 * hiss.
 *
 * Because this runs AFTER the call, we can see the whole signal and build a *smooth*
 * gain curve instead of guessing frame-by-frame. Three cooperating stages:
 *
 *   1. Envelope follower  — per-frame RMS, then an asymmetric one-pole smoother on the
 *                           gain (fast attack when pulling gain DOWN on loud speech,
 *                           slow release when pushing gain UP on quiet speech). This is
 *                           what removes the pumping.
 *   2. Noise gate         — an adaptive noise floor (low percentile of frame energy).
 *                           Frames at/below it are attenuated, so quiet gaps are not
 *                           boosted into audible hiss.
 *   3. Limiter            — soft-knee ceiling so that when boosted quiet speech meets a
 *                           sudden loud burst, it compresses instead of clipping.
 *
 * The net effect: every speaker ends up near the same target level, evenly and
 * naturally, with no breathing background.
 */
object AudioNormalizer {

    data class Config(
        val frameMs: Double = 20.0,
        /** Target RMS in normalized [-1,1] full scale. ~0.2 ≈ -14 dBFS, a comfortable level. */
        val targetRms: Double = 0.20,
        /** Weak far-end-only calls sit near the noise floor, so allow more make-up headroom. */
        val maxGain: Double = 20.0,
        val minGain: Double = 0.3,
        /** Gate OPENS when frame RMS ≥ noiseFloor × this. */
        val gateOpenFactor: Double = 2.0,
        /** Gate CLOSES only when RMS falls below noiseFloor × this (hysteresis; < open). */
        val gateCloseFactor: Double = 1.3,
        /** Once open, stay open at least this long so gaps between syllables don't chop speech. */
        val gateHoldMs: Double = 300.0,
        /** Gain during genuine (held-past) silence — a gentle duck, not a hard cut. */
        val silenceGain: Double = 0.5,
        /** Slow level envelope that drives the make-up gain (→ one stable gain per utterance). */
        val envAttackMs: Double = 40.0,
        val envReleaseMs: Double = 600.0,
        val attackMs: Double = 40.0,     // gain smoothing: reduce gain on loud speech
        val releaseMs: Double = 500.0,   // gain smoothing: raise gain on quiet speech
        val limiterCeiling: Double = 0.95,
    )

    /**
     * Everything the normalizer measured and decided, at 20 ms frame resolution.
     * Kept so the per-second metrics export ([AudioMetrics]) can document not just the
     * audio before/after, but the gain curve the normalizer actually applied.
     */
    data class Diagnostics(
        val cfg: Config,
        val frameLen: Int,            // samples per analysis frame
        val noiseFloor: Double,       // adaptive floor (10th percentile of frame RMS)
        val gateThreshold: Double,    // frames below this were treated as noise
        val frameRms: DoubleArray,    // raw-signal RMS per frame
        val rawGain: DoubleArray,     // target gain before smoothing
        val smoothGain: DoubleArray,  // gain actually applied (after envelope follower)
    )

    data class Result(val audio: WavIo.PcmAudio, val diagnostics: Diagnostics)

    fun normalize(input: WavIo.PcmAudio, cfg: Config = Config()): WavIo.PcmAudio =
        process(input, cfg).audio

    fun process(input: WavIo.PcmAudio, cfg: Config = Config()): Result {
        val x = input.samples
        val sr = input.sampleRate
        if (x.isEmpty()) {
            return Result(
                input,
                Diagnostics(cfg, 1, 0.0, 0.0, DoubleArray(0), DoubleArray(0), DoubleArray(0))
            )
        }

        val frameLen = (sr * cfg.frameMs / 1000.0).toInt().coerceAtLeast(1)
        val frameCount = (x.size + frameLen - 1) / frameLen

        // ---- Pass 1: per-frame RMS + adaptive noise floor -------------------------------
        val rms = DoubleArray(frameCount)
        for (f in 0 until frameCount) {
            val start = f * frameLen
            val end = minOf(start + frameLen, x.size)
            var acc = 0.0
            for (i in start until end) acc += x[i].toDouble() * x[i]
            rms[f] = sqrt(acc / (end - start))
        }

        val noiseFloor = percentile(rms, 0.10).coerceAtLeast(1e-4)
        val openThresh = noiseFloor * cfg.gateOpenFactor
        val closeThresh = noiseFloor * cfg.gateCloseFactor
        val holdFrames = (cfg.gateHoldMs / cfg.frameMs).toInt().coerceAtLeast(1)

        // ---- Gate with hysteresis + hold ------------------------------------------------
        // A weak far-end-only call sits right at the noise floor, so a naive per-frame gate
        // toggles on every syllable and the gain pumps ("unstable voice"). Separate open/close
        // thresholds plus a hold keep the gate open across the short gaps inside speech.
        val gateOpen = BooleanArray(frameCount)
        var open = false
        var held = 0
        for (f in 0 until frameCount) {
            val r = rms[f]
            if (r >= openThresh) { open = true; held = 0 }
            else if (open) {
                if (r < closeThresh) { held++; if (held >= holdFrames) open = false }
                else held = 0   // between close and open thresholds: still speech-ish, keep open
            }
            gateOpen[f] = open
        }

        // ---- Slow level envelope that drives the make-up gain ---------------------------
        // Basing gain on a slow envelope (not the instantaneous frame RMS) yields ONE stable
        // gain per utterance instead of chasing every syllable — this is what removes pumping.
        val envAtt = 1.0 - exp(-cfg.frameMs / cfg.envAttackMs)
        val envRel = 1.0 - exp(-cfg.frameMs / cfg.envReleaseMs)
        val env = DoubleArray(frameCount)
        env[0] = rms[0]
        for (f in 1 until frameCount) {
            val prev = env[f - 1]
            val coef = if (rms[f] > prev) envAtt else envRel
            env[f] = prev + (rms[f] - prev) * coef
        }

        // ---- Raw target gain per frame --------------------------------------------------
        val rawGain = DoubleArray(frameCount)
        for (f in 0 until frameCount) {
            rawGain[f] = if (gateOpen[f]) {
                (cfg.targetRms / maxOf(env[f], openThresh)).coerceIn(cfg.minGain, cfg.maxGain)
            } else {
                cfg.silenceGain
            }
        }

        // ---- Envelope follower: asymmetric one-pole smoothing of the gain curve ---------
        val attackCoef = 1.0 - exp(-cfg.frameMs / cfg.attackMs)
        val releaseCoef = 1.0 - exp(-cfg.frameMs / cfg.releaseMs)
        val smoothGain = DoubleArray(frameCount)
        smoothGain[0] = rawGain[0]
        for (f in 1 until frameCount) {
            val prev = smoothGain[f - 1]
            val target = rawGain[f]
            // target < prev  => we must LOWER gain (signal got louder) => attack (fast)
            val coef = if (target < prev) attackCoef else releaseCoef
            smoothGain[f] = prev + (target - prev) * coef
        }

        // ---- Pass 2: apply per-sample interpolated gain + soft limiter ------------------
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val gain = interpolatedGain(smoothGain, i, frameLen)
            out[i] = softLimit(x[i].toDouble() * gain, cfg.limiterCeiling).toFloat()
        }

        return Result(
            WavIo.PcmAudio(out, sr),
            Diagnostics(cfg, frameLen, noiseFloor, openThresh, rms, rawGain, smoothGain)
        )
    }

    /** Linear interpolation of the frame gain, sampled at frame centers, for zipper-free gain. */
    private fun interpolatedGain(frameGain: DoubleArray, sampleIndex: Int, frameLen: Int): Double {
        val pos = (sampleIndex.toDouble() / frameLen) - 0.5   // frame centers sit at 0.5, 1.5, ...
        if (pos <= 0.0) return frameGain[0]
        val f0 = pos.toInt()
        if (f0 >= frameGain.size - 1) return frameGain[frameGain.size - 1]
        val t = pos - f0
        return frameGain[f0] * (1 - t) + frameGain[f0 + 1] * t
    }

    /** Soft-knee limiter: linear below the ceiling, tanh-compressed above it. */
    private fun softLimit(v: Double, ceiling: Double): Double {
        val a = abs(v)
        if (a <= ceiling) return v
        val over = a - ceiling
        val room = 1.0 - ceiling
        val compressed = ceiling + room * kotlin.math.tanh(over / room)
        return if (v >= 0) compressed else -compressed
    }

    private fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.clone().also { it.sort() }
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    @Suppress("unused")
    private fun dbToLin(db: Double) = exp(db / 20.0 * ln(10.0))
}
