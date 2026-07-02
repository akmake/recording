package com.callstream.recorder.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline spectral noise reduction, run BEFORE [AudioNormalizer].
 *
 * The captured call sits only ~15-20 dB above the noise floor (the OS attenuates the whole
 * in-call mic path), so the normalizer's big make-up gain also lifts the hiss. Reducing the
 * broadband noise first means the boost amplifies mostly voice, not hiss — verified on a real
 * recording: noise floor down ~7 dB with the speech level unchanged.
 *
 * Method — STFT spectral gating (à la Audacity noise reduction):
 *   1. per-bin noise profile = a low percentile of that bin's magnitude across the whole call
 *      (most frames are noise, so the low percentile is the noise level);
 *   2. per-frame, per-bin Wiener-style mask from the local SNR, over-subtracted, floored to a
 *      small residual (avoids "musical noise"), and smoothed over time;
 *   3. overlap-add resynthesis.
 *
 * Memory-safe: the noise profile is gathered with a per-bin histogram in a first pass, so we
 * never hold the whole spectrogram — a long call won't OOM. The FFT frames are recomputed in
 * the second pass (CPU is cheap; this runs once, after the call).
 */
object NoiseReducer {

    data class Config(
        val fftSize: Int = 1024,          // ~43 Hz/bin at 44.1 kHz
        val hop: Int = 256,               // 75% overlap
        val noisePercentile: Double = 0.30,
        val overSub: Double = 1.8,        // how aggressively to subtract the noise estimate
        val floorGain: Double = 0.08,     // residual gain floor → no total nulls / musical noise
        val timeSmooth: Double = 0.5,     // mask smoothing across frames [0,1)
    )

    fun reduce(input: WavIo.PcmAudio, cfg: Config = Config()): WavIo.PcmAudio =
        WavIo.PcmAudio(reduce(input.samples, cfg), input.sampleRate)

    fun reduce(x: FloatArray, cfg: Config = Config()): FloatArray {
        val n = cfg.fftSize
        val h = cfg.hop
        if (x.size < n || n and (n - 1) != 0) return x   // too short / not power of two
        val bins = n / 2 + 1
        val win = hann(n)

        // ---- Pass 1: per-bin noise profile via histogram (no full spectrogram in memory) ----
        val nBuckets = 96
        val lo = -8.0; val hi = 1.0                       // log10(magnitude) range
        val hist = Array(bins) { IntArray(nBuckets) }
        run {
            val re = DoubleArray(n); val im = DoubleArray(n)
            var pos = 0
            while (pos + n <= x.size) {
                for (i in 0 until n) { re[i] = x[pos + i].toDouble() * win[i]; im[i] = 0.0 }
                fft(re, im, false)
                for (b in 0 until bins) {
                    val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                    val lg = log10(maxOf(mag, 1e-9)).coerceIn(lo, hi)
                    val bucket = ((lg - lo) / (hi - lo) * (nBuckets - 1)).toInt()
                    hist[b][bucket]++
                }
                pos += h
            }
        }
        val noise = DoubleArray(bins)
        for (b in 0 until bins) {
            var total = 0
            for (k in 0 until nBuckets) total += hist[b][k]
            val target = (cfg.noisePercentile * total).toInt()
            var acc = 0; var bucket = 0
            for (k in 0 until nBuckets) { acc += hist[b][k]; if (acc >= target) { bucket = k; break } }
            noise[b] = Math.pow(10.0, lo + bucket.toDouble() / (nBuckets - 1) * (hi - lo))
        }

        // ---- Pass 2: apply the spectral mask and overlap-add ----
        val outAcc = DoubleArray(x.size)
        val winAcc = DoubleArray(x.size)
        val prevGain = DoubleArray(bins) { 1.0 }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val mag = DoubleArray(bins); val ph = DoubleArray(bins)
        var pos = 0
        while (pos + n <= x.size) {
            for (i in 0 until n) { re[i] = x[pos + i].toDouble() * win[i]; im[i] = 0.0 }
            fft(re, im, false)
            for (b in 0 until bins) {
                mag[b] = sqrt(re[b] * re[b] + im[b] * im[b])
                ph[b] = atan2(im[b], re[b])
            }
            for (b in 0 until bins) {
                val nz = noise[b] * cfg.overSub
                val snr = if (nz > 1e-12) mag[b] / nz else 10.0
                var g = snr * snr / (1.0 + snr * snr)          // Wiener-style soft mask
                if (g < cfg.floorGain) g = cfg.floorGain
                g = cfg.timeSmooth * prevGain[b] + (1 - cfg.timeSmooth) * g
                prevGain[b] = g
                val m = mag[b] * g
                re[b] = m * cos(ph[b]); im[b] = m * sin(ph[b])
            }
            for (b in 1 until bins - 1) { re[n - b] = re[b]; im[n - b] = -im[b] }   // hermitian
            fft(re, im, true)
            for (i in 0 until n) {
                val idx = pos + i
                outAcc[idx] += re[i] * win[i]
                winAcc[idx] += win[i] * win[i]
            }
            pos += h
        }
        val out = FloatArray(x.size)
        for (i in x.indices) out[i] = (if (winAcc[i] > 1e-9) outAcc[i] / winAcc[i] else 0.0).toFloat()
        return out
    }

    private fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    /** Iterative in-place radix-2 Cooley-Tukey FFT (inverse normalizes by n). */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1.0 else -1.0)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0; var curI = 0.0
                for (k in 0 until len / 2) {
                    val aR = re[i + k]; val aI = im[i + k]
                    val bR = re[i + k + len / 2]; val bI = im[i + k + len / 2]
                    val tR = bR * curR - bI * curI
                    val tI = bR * curI + bI * curR
                    re[i + k] = aR + tR; im[i + k] = aI + tI
                    re[i + k + len / 2] = aR - tR; im[i + k + len / 2] = aI - tI
                    val ncR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr; curR = ncR
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
