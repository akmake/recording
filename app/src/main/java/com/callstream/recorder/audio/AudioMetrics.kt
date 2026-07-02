package com.callstream.recorder.audio

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-second measurement of a recording, exported as CSV so it can be inspected — or
 * uploaded to an LLM — to judge where the normalizer helped and where it hurt.
 *
 * Two files are written next to each recording:
 *   - "<name>.raw.csv"        — the signal as captured, before any processing
 *   - "<name>.processed.csv"  — the same seconds after normalization, plus the gain the
 *                               normalizer actually applied and how hard the limiter worked
 *
 * The two files share the same row grid (second 0, 1, 2, ...) and the same `active_ratio`
 * column (speech-activity is a property of the source signal), so row N of one file is
 * directly comparable to row N of the other.
 */
object AudioMetrics {

    enum class Stage { RAW, PROCESSED }

    fun writeCsv(
        target: File,
        audio: WavIo.PcmAudio,
        diag: AudioNormalizer.Diagnostics,
        stage: Stage,
        recordingName: String,
        audioSource: String = "unknown",
    ) {
        val sb = StringBuilder(16 * 1024)
        writeHeader(sb, audio, diag, stage, recordingName, audioSource)
        writeRows(sb, audio, diag, stage)
        target.writeText(sb.toString())
    }

    // ---- Header: global context an analyst (human or AI) needs to read the rows ---------

    private fun writeHeader(
        sb: StringBuilder,
        audio: WavIo.PcmAudio,
        diag: AudioNormalizer.Diagnostics,
        stage: Stage,
        recordingName: String,
        audioSource: String,
    ) {
        val cfg = diag.cfg
        val durationS = audio.samples.size.toDouble() / audio.sampleRate
        sb.appendLine("# Reko audio metrics v1")
        sb.appendLine("# recording=$recordingName")
        sb.appendLine("# stage=${if (stage == Stage.RAW) "raw (before normalization)" else "processed (after normalization)"}")
        sb.appendLine("# audio_source=$audioSource (capture path — determines how much of the far end is present in the raw signal)")
        sb.appendLine("# sample_rate_hz=${audio.sampleRate}")
        sb.appendLine("# duration_s=${fmt(durationS, 2)}")
        sb.appendLine("# noise_floor_rms=${fmt(diag.noiseFloor, 6)} (adaptive: 10th percentile of ${fmt(cfg.frameMs, 0)}ms frame RMS of the raw signal)")
        sb.appendLine("# gate_threshold_rms=${fmt(diag.gateThreshold, 6)} (frames below this were treated as noise/silence)")
        sb.appendLine("# normalizer_config: target_rms=${fmt(cfg.targetRms, 2)} gain_range=[${fmt(cfg.minGain, 1)},${fmt(cfg.maxGain, 1)}] attack_ms=${fmt(cfg.attackMs, 0)} release_ms=${fmt(cfg.releaseMs, 0)} limiter_ceiling=${fmt(cfg.limiterCeiling, 2)}")
        sb.appendLine("# column meanings:")
        sb.appendLine("#   second         = 1-second window index, 0-based")
        sb.appendLine("#   rms/peak       = level in linear full-scale [0..1]; *_dbfs = same in dBFS (0 = full scale)")
        sb.appendLine("#   crest_db       = peak-to-RMS ratio in dB (high = spiky/transient, low = dense/compressed)")
        sb.appendLine("#   frame_rms_min/max = quietest/loudest ${fmt(cfg.frameMs, 0)}ms frame inside the second (intra-second dynamics)")
        sb.appendLine("#   active_ratio   = share of frames above the gate threshold, measured on the RAW signal (speech activity)")
        sb.appendLine("#   clipped_ratio  = share of samples at digital full scale (>= 0.999)")
        if (stage == Stage.PROCESSED) {
            sb.appendLine("#   gain_avg/min/max = normalizer gain applied during the second (1.0 = untouched, >1 boosted, <1 attenuated)")
            sb.appendLine("#   limited_ratio  = share of samples pushed into the soft limiter (>= ceiling ${fmt(cfg.limiterCeiling, 2)})")
        }
    }

    // ---- Rows ----------------------------------------------------------------------------

    private fun writeRows(
        sb: StringBuilder,
        audio: WavIo.PcmAudio,
        diag: AudioNormalizer.Diagnostics,
        stage: Stage,
    ) {
        val base = "second,rms,rms_dbfs,peak,peak_dbfs,crest_db,frame_rms_min,frame_rms_max,active_ratio,clipped_ratio"
        sb.appendLine(if (stage == Stage.PROCESSED) "$base,gain_avg,gain_min,gain_max,limited_ratio" else base)

        val x = audio.samples
        val sr = audio.sampleRate
        if (x.isEmpty() || sr <= 0) return
        val frameLen = diag.frameLen.coerceAtLeast(1)
        val seconds = (x.size + sr - 1) / sr

        for (s in 0 until seconds) {
            val start = s * sr
            val end = min(start + sr, x.size)
            val n = end - start

            var acc = 0.0
            var peak = 0.0
            var clipped = 0
            var limited = 0
            for (i in start until end) {
                val v = x[i].toDouble()
                acc += v * v
                val a = abs(v)
                if (a > peak) peak = a
                if (a >= 0.999) clipped++
                if (a >= diag.cfg.limiterCeiling) limited++
            }
            val rms = sqrt(acc / n)

            // Intra-second frame stats on THIS stage's samples (20ms grid, aligned to the file start).
            var frMin = Double.MAX_VALUE
            var frMax = 0.0
            var f = start / frameLen
            while (f * frameLen < end) {
                val fs = maxOf(f * frameLen, start)
                val fe = min((f + 1) * frameLen, end)
                var fAcc = 0.0
                for (i in fs until fe) fAcc += x[i].toDouble() * x[i]
                val fr = sqrt(fAcc / (fe - fs))
                if (fr < frMin) frMin = fr
                if (fr > frMax) frMax = fr
                f++
            }
            if (frMin == Double.MAX_VALUE) frMin = 0.0

            // Activity + gain come from the normalizer's raw-signal frame grid, so both
            // CSVs report the same activity for the same second.
            val f0 = start / frameLen
            val f1 = min((end - 1) / frameLen, diag.frameRms.size - 1)
            var active = 0
            var frames = 0
            var gSum = 0.0
            var gMin = Double.MAX_VALUE
            var gMax = 0.0
            if (f1 >= f0 && diag.frameRms.isNotEmpty()) {
                for (fi in f0..f1) {
                    frames++
                    if (diag.frameRms[fi] >= diag.gateThreshold) active++
                    val g = diag.smoothGain[fi]
                    gSum += g
                    if (g < gMin) gMin = g
                    if (g > gMax) gMax = g
                }
            }
            val activeRatio = if (frames > 0) active.toDouble() / frames else 0.0

            sb.append(s).append(',')
                .append(fmt(rms, 6)).append(',')
                .append(fmt(dbfs(rms), 2)).append(',')
                .append(fmt(peak, 6)).append(',')
                .append(fmt(dbfs(peak), 2)).append(',')
                .append(fmt(dbfs(peak) - dbfs(rms), 2)).append(',')
                .append(fmt(frMin, 6)).append(',')
                .append(fmt(frMax, 6)).append(',')
                .append(fmt(activeRatio, 3)).append(',')
                .append(fmt(clipped.toDouble() / n, 4))
            if (stage == Stage.PROCESSED) {
                if (frames > 0) {
                    sb.append(',').append(fmt(gSum / frames, 3))
                        .append(',').append(fmt(gMin, 3))
                        .append(',').append(fmt(gMax, 3))
                } else {
                    sb.append(",1.000,1.000,1.000")
                }
                sb.append(',').append(fmt(limited.toDouble() / n, 4))
            }
            sb.appendLine()
        }
    }

    private fun dbfs(v: Double): Double = if (v <= 1e-9) -120.0 else 20.0 * log10(v)

    private fun fmt(v: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", v)
}
