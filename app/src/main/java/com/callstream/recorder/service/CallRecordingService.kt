package com.callstream.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.callstream.recorder.R
import com.callstream.recorder.audio.AudioNormalizer
import com.callstream.recorder.audio.WavIo
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.Recording
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Foreground service that records the call audio and, when the call ends, runs the
 * offline [AudioNormalizer] so both speakers come out at an even level.
 *
 * Started by [CallAccessibilityService] with the resolved contact name / number so the
 * saved recording is already labeled.
 */
class CallRecordingService : Service() {

    companion object {
        private const val TAG = "CallRecordingService"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_NUMBER = "extra_number"
        const val EXTRA_TYPE = "extra_type"
        const val ACTION_START = "com.callstream.recorder.START"
        const val ACTION_STOP = "com.callstream.recorder.STOP"

        private const val CHANNEL_ID = "callstream_recording"
        private const val NOTIF_ID = 42

        private const val SAMPLE_RATE = 44100
        // Preference order: VOICE_CALL captures both legs on privileged ROMs; the rest
        // are progressively more permissive fallbacks for ordinary phones.
        private val SOURCE_PRIORITY = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }

    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private var windowManager: WindowManager? = null
    private var overlay: View? = null

    private var displayName: String? = null
    private var number: String? = null
    private var callType: CallType = CallType.UNKNOWN
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecordingAndFinish()
                return START_NOT_STICKY
            }
            else -> {
                displayName = intent?.getStringExtra(EXTRA_NAME)
                number = intent?.getStringExtra(EXTRA_NUMBER)
                callType = runCatching {
                    CallType.valueOf(intent?.getStringExtra(EXTRA_TYPE) ?: "UNKNOWN")
                }.getOrDefault(CallType.UNKNOWN)
                startForegroundWithNotif()
                startRecording()
            }
        }
        return START_STICKY
    }

    // ---- Recording ---------------------------------------------------------------------

    private fun startRecording() {
        if (recording) return
        recording = true
        startedAt = System.currentTimeMillis()

        recordThread = thread(name = "call-record") {
            val rawFile = File(cacheDir, "raw_$startedAt.pcm")
            var written = 0L
            var record: AudioRecord? = null
            try {
                record = openRecorder() ?: run {
                    Log.e(TAG, "No usable audio source; aborting")
                    return@thread
                }
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)
                val buffer = ByteArray(minBuf)

                record.startRecording()
                BufferedOutputStream(FileOutputStream(rawFile)).use { out ->
                    while (recording) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n > 0) {
                            out.write(buffer, 0, n)
                            written += n
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                record?.release()
            }

            if (written > 0) finalizeRecording(rawFile, written) else rawFile.delete()
        }
    }

    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        for (source in SOURCE_PRIORITY) {
            try {
                @Suppress("MissingPermission")
                val r = AudioRecord(
                    source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Using audio source $source")
                    return r
                }
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "Source $source unavailable: ${e.message}")
            }
        }
        return null
    }

    /** Wrap raw PCM as WAV, normalize it, write the labeled output + sidecar. */
    private fun finalizeRecording(rawFile: File, pcmBytes: Long) {
        try {
            val duration = System.currentTimeMillis() - startedAt
            val rawWav = File(cacheDir, "raw_$startedAt.wav")
            // Wrap the headerless raw PCM into a valid WAV (header + payload).
            prependWavHeader(rawWav, rawFile, pcmBytes)
            rawFile.delete()

            val loaded = WavIo.readPcm16(rawWav)
            val normalized = AudioNormalizer.normalize(loaded)

            val outDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
            val safeLabel = (displayName ?: number ?: "unknown")
                .replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
            val outWav = File(outDir, "${stamp}_$safeLabel.wav")
            WavIo.writePcm16(outWav, normalized)
            rawWav.delete()

            val rec = Recording(
                wavPath = outWav.absolutePath,
                displayName = displayName,
                number = number,
                type = callType,
                startedAtMillis = startedAt,
                durationMillis = duration,
            )
            rec.sidecarFile().writeText(rec.toJson())
            Log.i(TAG, "Saved recording: ${outWav.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Finalize failed", e)
        }
    }

    /** Writes raw PCM into a fresh WAV with a correct header (raw file has no header). */
    private fun prependWavHeader(target: File, rawPcm: File, pcmBytes: Long) {
        FileOutputStream(target).use { fos ->
            // Header first (placeholder length values fixed by writeHeaderInPlace below),
            // then the PCM payload.
            fos.write(ByteArray(44))
            rawPcm.inputStream().use { it.copyTo(fos) }
        }
        WavIo.writeHeaderInPlace(target, SAMPLE_RATE, 1, 16, pcmBytes)
    }

    private fun stopRecordingAndFinish() {
        recording = false
        recordThread?.join(2000)
        recordThread = null
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recording = false
        removeOverlay()
        super.onDestroy()
    }

    // ---- Foreground notification -------------------------------------------------------

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_in_progress))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        showOverlay()
    }

    // ---- Floating overlay --------------------------------------------------------------

    private fun showOverlay() {
        if (overlay != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_recorder, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24; y = 160
        }

        view.findViewById<TextView>(R.id.txt_status)?.text = getString(R.string.recording_in_progress)
        view.findViewById<ImageView>(R.id.btn_close)?.setOnClickListener {
            val stop = Intent(this, CallRecordingService::class.java).apply { action = ACTION_STOP }
            startService(stop)
        }

        windowManager?.addView(view, params)
        overlay = view
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
    }
}
