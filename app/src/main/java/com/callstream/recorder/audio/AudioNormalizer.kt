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
        val maxGain: Double = 12.0,
        val minGain: Double = 0.3,
        /** Frames whose RMS is below noiseFloor * this are treated as noise/silence. */
        val gateOpenFactor: Double = 2.5,
        /** Residual gain applied to gated (noise) frames — suppresses hiss without hard cuts. */
        val gateFloorGain: Double = 0.15,
        val attackMs: Double = 10.0,     // fast: reduce gain on loud speech
        val releaseMs: Double = 450.0,   // slow: raise gain on quiet speech
        val limiterCeiling: Double = 0.95,
    )

    fun normalize(input: WavIo.PcmAudio, cfg: Config = Config()): WavIo.PcmAudio {
        val x = input.samples
        val sr = input.sampleRate
        if (x.isEmpty()) return input

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
        val gateThreshold = noiseFloor * cfg.gateOpenFactor

        // ---- Raw target gain per frame --------------------------------------------------
        val rawGain = DoubleArray(frameCount)
        for (f in 0 until frameCount) {
            rawGain[f] = if (rms[f] >= gateThreshold) {
                (cfg.targetRms / rms[f]).coerceIn(cfg.minGain, cfg.maxGain)
            } else {
                cfg.gateFloorGain
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

        return WavIo.PcmAudio(out, sr)
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
