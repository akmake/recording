package com.callstream.recorder.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV (PCM 16-bit) reader/writer.
 *
 * We record raw PCM to a headerless temp file during the call (cheap, streamable),
 * then wrap it with a WAV header when the call ends. The normalizer reads that WAV
 * back into float samples, processes it, and writes a new WAV.
 */
object WavIo {

    data class PcmAudio(
        val samples: FloatArray,   // mono, normalized to [-1, 1]
        val sampleRate: Int,
    )

    /** Writes a 44-byte canonical WAV header in front of an existing raw-PCM payload. */
    fun writeHeaderInPlace(target: File, sampleRate: Int, channels: Int, bitsPerSample: Int, pcmDataBytes: Long) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = pcmDataBytes + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                       // Subchunk1Size for PCM
        header.putShort(1)                      // AudioFormat = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(pcmDataBytes.toInt())

        RandomAccessFile(target, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    /** Reads a mono/stereo 16-bit PCM WAV and returns mono float samples. */
    fun readPcm16(file: File): PcmAudio {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "WAV too short: ${file.name}" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Walk chunks to locate 'fmt ' and 'data' rather than assuming a fixed layout.
        bb.position(12)
        var sampleRate = 44100
        var channels = 1
        var dataOffset = 44
        var dataLen = bytes.size - 44
        while (bb.position() + 8 <= bytes.size) {
            val id = ByteArray(4).also { bb.get(it) }
            val size = bb.int
            val tag = String(id)
            if (tag == "fmt ") {
                val fmtStart = bb.position()
                bb.short                        // audioFormat
                channels = bb.short.toInt()
                sampleRate = bb.int
                bb.position(fmtStart + size)
            } else if (tag == "data") {
                dataOffset = bb.position()
                dataLen = size
                break
            } else {
                bb.position(bb.position() + size)
            }
        }

        val frameCount = dataLen / 2
        val src = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        val mono = FloatArray(frameCount / channels)
        var i = 0
        while (i < mono.size) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += src.short.toFloat() / 32768f
            }
            mono[i] = acc / channels
            i++
        }
        return PcmAudio(mono, sampleRate)
    }

    /** Writes mono float samples as a 16-bit PCM WAV. */
    fun writePcm16(file: File, audio: PcmAudio) {
        val n = audio.samples.size
        val pcmBytes = n * 2
        val out = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()); out.putInt(36 + pcmBytes)
        out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()); out.putInt(16)
        out.putShort(1); out.putShort(1)
        out.putInt(audio.sampleRate)
        out.putInt(audio.sampleRate * 2)
        out.putShort(2); out.putShort(16)
        out.put("data".toByteArray()); out.putInt(pcmBytes)
        for (s in audio.samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            out.putShort(v.toShort())
        }
        file.writeBytes(out.array())
    }
}
