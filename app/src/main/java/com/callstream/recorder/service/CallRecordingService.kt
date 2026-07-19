package com.callstream.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import com.callstream.recorder.RecorderPreferences
import com.callstream.recorder.audio.AudioMetrics
import com.callstream.recorder.audio.AudioNormalizer
import com.callstream.recorder.audio.NoiseReducer
import com.callstream.recorder.audio.WavIo
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.Recording
import com.callstream.recorder.diagnostics.AppLog
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
        const val EXTRA_LOG_WATERMARK = "extra_log_watermark"
        const val ACTION_START = "com.callstream.recorder.START"
        const val ACTION_STOP = "com.callstream.recorder.STOP"
        // A call is up but auto-record is off for it: come up WITHOUT capturing, just to put a
        // record button on screen. Without this there is no UI at all during such a call, so
        // recording by hand is impossible.
        const val ACTION_ARM = "com.callstream.recorder.ARM"
        // Sent by CallAccessibilityService mid-call when it scrapes the name/number off the
        // in-call screen — the live, permission-free source of the label.
        const val ACTION_UPDATE_LABEL = "com.callstream.recorder.UPDATE_LABEL"
        const val ACTION_HIDE_OVERLAY = "com.callstream.recorder.HIDE_OVERLAY"
        // Set on ACTION_STOP by the user's own stop button: the call is still up, so once the
        // segment is saved we go back to ARMED instead of tearing down — the user can record
        // another stretch of the same call. A call-end stop carries no such flag.
        const val EXTRA_REARM = "extra_rearm"
        const val EXTRA_CALL_ENDED = "extra_call_ended"
        const val EXTRA_MANUAL = "extra_manual"

        private const val CHANNEL_ID = "callstream_recording"
        private const val NOTIF_ID = 42

        // Overlay record button: white when idle, coral while capturing (matches the app's accent).
        private const val COLOR_IDLE = 0xFFFFFFFF.toInt()
        private const val COLOR_RECORDING = 0xFFFF6B5E.toInt()

        private const val SAMPLE_RATE = 44100
        // Preference order for capturing RAW material (our AudioNormalizer balances it later):
        //   VOICE_CALL       — both legs on privileged ROMs; auto-skipped when it streams
        //                      silence (the common case on ordinary phones).
        //   VOICE_RECOGNITION — raw mic with NO platform echo cancellation. This is the path
        //                      that actually works: near end loud, far end faint (earpiece
        //                      leak) — exactly the imbalance the normalizer is built to fix.
        //   MIC              — last-resort raw mic.
        // VOICE_COMMUNICATION is intentionally absent: it forces the OS echo canceller, whose
        // job is to erase the earpiece leak of the far party — i.e. it deletes the other
        // person, leaving only your own voice. That is the regression that broke recording.
        private val SOURCE_PRIORITY = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }

    /** ARMED = call up, button on screen, nothing captured yet. RECORDING = capturing. */
    private enum class State { ARMED, RECORDING }

    @Volatile private var recording = false
    // null = the service isn't up for a call (a bare ACTION_STOP/UPDATE_LABEL may still have
    // instantiated it); anything else is what the overlay and the notification are painted for.
    @Volatile private var state: State? = null
    @Volatile private var reArmAfterStop = false
    private var recordThread: Thread? = null
    private var windowManager: WindowManager? = null
    private var overlay: View? = null

    // @Volatile: written on the main thread (onStartCommand), read on the record thread
    // (finalizeRecording). The ACTION_STOP handler may refine these from the call log.
    @Volatile private var displayName: String? = null
    @Volatile private var number: String? = null
    private var callType: CallType = CallType.UNKNOWN
    private var startedAt = 0L
    // Call-log date captured at call start; finalize waits for the first newer row (this call's).
    @Volatile private var logWatermark = 0L
    @Volatile private var usedSource: String = "unknown"
    @Volatile private var manuallyStarted = false
    @Volatile private var lastStopWasCallEnd = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Every path returns START_NOT_STICKY: a restart by the system carries a null intent, i.e. no
     * call behind it — and capturing from one would mean recording with no call in progress, which
     * is exactly what a user who turned auto-record off does not want. Restarting cannot recover a
     * lost capture anyway; the accessibility service is what drives us, and it will call again.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {                       // sticky restart / redelivery: nothing to do
            if (state == null) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_HIDE_OVERLAY -> {
                removeOverlay()
                AppLog.i(this, "overlay_hidden", "recording_continues=${state == State.RECORDING}")
                return START_NOT_STICKY
            }
            ACTION_ARM -> {
                adoptExtras(intent)
                AppLog.i(this, "recorder_armed", "state=$state type=$callType")
                if (state == State.RECORDING) return START_NOT_STICKY  // label extras were still adopted
                state = State.ARMED
                // Go foreground even though we capture nothing yet: it is what puts the button on
                // screen, and holding a microphone-type foreground service from the start is also
                // what lets the mic actually open later, mid-call, from the background.
                startForegroundWithNotif()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                // The stopper may carry a fallback number/name — keep it as a hint. The
                // authoritative cellular label is resolved from the call log during finalize.
                // Guard against nulls so the overlay's close button doesn't wipe a good label.
                intent.getStringExtra(EXTRA_NAME)?.let { displayName = it }
                intent.getStringExtra(EXTRA_NUMBER)?.let { number = it }
                val callEnded = intent.getBooleanExtra(EXTRA_CALL_ENDED, false)
                lastStopWasCallEnd = callEnded
                val keepManualStandby = callEnded && !RecorderPreferences.isAutoRecordEnabled(this)
                AppLog.i(this, "recorder_stop_requested", "state=$state callEnded=$callEnded keepStandby=$keepManualStandby")
                if (state == State.RECORDING) {
                    reArmAfterStop = intent.getBooleanExtra(EXTRA_REARM, false) || keepManualStandby
                    // Just stop capturing; the record thread finalizes (incl. the call-log poll)
                    // and only then tears the service down or re-arms it, so the service Context
                    // stays valid throughout.
                    recording = false
                } else if (!keepManualStandby) {
                    // Armed (or never started): there is no record thread to finalize and hand
                    // the teardown to, so retire here — otherwise the service would linger.
                    finishService()
                } else {
                    startForegroundWithNotif()
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_LABEL -> {
                // Live label scraped off the in-call screen. Adopt any non-blank value; a blank
                // scrape never wipes a name/number we already have.
                intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                intent.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() }?.let { number = it }
                AppLog.i(this, "label_updated", "name=${displayName ?: "missing"} number=${number ?: "missing"}")
                // Delivered to a service that isn't up for a call (the user dismissed the overlay,
                // and startService just instantiated us): don't linger as a zombie.
                if (state == null) stopSelf()
                return START_NOT_STICKY
            }
            else -> {  // ACTION_START — auto-record, or the user pressed record mid-call
                adoptExtras(intent)
                if (state == State.RECORDING) return START_NOT_STICKY  // adopt better fallback labels
                state = State.RECORDING
                manuallyStarted = intent.getBooleanExtra(EXTRA_MANUAL, false) || callType == CallType.UNKNOWN
                AppLog.i(this, "recording_start_requested", "type=$callType manual=$manuallyStarted name=${displayName ?: "missing"}")
                startForegroundWithNotif()
                startRecording()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Adopts whatever label/type the intent carries, keeping what we already know when it carries
     * nothing: an ACTION_START fired by the user's record button has no extras, and must not wipe
     * the label that ACTION_ARM — and the live screen scrape since — already gave us.
     */
    private fun adoptExtras(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_NAME)?.let { displayName = it }
        intent.getStringExtra(EXTRA_NUMBER)?.let { number = it }
        if (intent.hasExtra(EXTRA_LOG_WATERMARK)) {
            logWatermark = intent.getLongExtra(EXTRA_LOG_WATERMARK, 0L)
        }
        if (intent.hasExtra(EXTRA_TYPE)) {
            callType = runCatching {
                CallType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "UNKNOWN")
            }.getOrDefault(CallType.UNKNOWN)
        }
        if (intent.getBooleanExtra(EXTRA_MANUAL, false)) manuallyStarted = true
    }

    // ---- Recording ---------------------------------------------------------------------

    private fun startRecording() {
        if (recording) return
        recording = true
        startedAt = System.currentTimeMillis()
        AppLog.i(this, "capture_started", "startedAt=$startedAt")

        recordThread = thread(name = "call-record") {
            val rawFile = File(cacheDir, "raw_$startedAt.pcm")
            var written = 0L
            try {
                var record: AudioRecord? = null
                try {
                    // openRecorder() returns an already-started, signal-verified recorder.
                    record = openRecorder()
                    if (record == null) {
                        Log.e(TAG, "No usable audio source; aborting")
                        AppLog.e(this, "no_audio_source")
                    } else {
                        val buffer = ByteArray(readBufferSize())
                        BufferedOutputStream(FileOutputStream(rawFile)).use { out ->
                            while (recording) {
                                val n = record.read(buffer, 0, buffer.size)
                                if (n > 0) {
                                    out.write(buffer, 0, n)
                                    written += n
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording failed", e)
                    AppLog.e(this, "capture_failed", "written=$written", e)
                } finally {
                    try { record?.stop() } catch (_: Exception) {}
                    record?.release()
                }

                AppLog.i(this, "capture_stopped", "bytes=$written durationMs=${System.currentTimeMillis() - startedAt}")
                if (written > 0) finalizeRecording(rawFile, written) else {
                    rawFile.delete()
                    AppLog.e(this, "empty_recording_discarded")
                }
            } finally {
                // Always tear down — even if capture never started — so the foreground service
                // never leaks. Finalize (including the call-log poll) has completed by here.
                finishService()
            }
        }
    }

    private fun readBufferSize(): Int =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

    /**
     * Returns the first capture source that is not merely initialized but actually *delivers
     * audio*, already started and recording.
     *
     * Why the live probe: on ordinary phones VOICE_CALL usually initializes fine yet streams
     * pure digital silence (the OS quietly denies two-leg capture). The old code accepted any
     * INITIALIZED source, locked onto that dead stream, and saved empty recordings. We now
     * read a short burst and only lock onto a source that yields real, non-zero samples.
     *
     * A raw mic source (VOICE_RECOGNITION / MIC) that merely probes quiet at call setup — the
     * line may still be ringing — is remembered as a fallback so we never abort on a source
     * that is valid but hasn't started carrying call audio yet.
     */
    private fun openRecorder(): AudioRecord? {
        val bufSize = readBufferSize() * 2
        var fallback: AudioRecord? = null
        for (source in SOURCE_PRIORITY) {
            var r: AudioRecord? = null
            try {
                @Suppress("MissingPermission")
                r = AudioRecord(
                    source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }

                // Keep the OS from pre-processing our raw material (see disableOsEffects).
                disableOsEffects(r.audioSessionId)
                r.startRecording()

                if (deliversAudio(r)) {
                    Log.i(TAG, "Using audio source $source (live signal confirmed)")
                    // A live source beats any silent-probing fallback we were holding.
                    fallback?.let { runCatching { it.stop(); it.release() } }
                    usedSource = sourceName(source)
                    return r
                }

                val isMic = source == MediaRecorder.AudioSource.VOICE_RECOGNITION ||
                    source == MediaRecorder.AudioSource.MIC
                if (isMic && fallback == null) {
                    Log.w(TAG, "Source $source probed silent; keeping as fallback")
                    fallback = r  // don't release: call audio may start after ringing
                    usedSource = sourceName(source) + " (silent probe)"
                } else {
                    Log.w(TAG, "Source $source delivered only silence; trying next")
                    r.stop(); r.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Source $source unavailable: ${e.message}")
                try { r?.release() } catch (_: Exception) {}
            }
        }
        if (fallback != null) Log.i(TAG, "Using fallback mic source (probed silent at setup)")
        return fallback
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source_$source"
    }

    /** Reads up to ~500 ms and reports whether any genuinely non-zero sample arrived. */
    private fun deliversAudio(record: AudioRecord): Boolean {
        val probe = ShortArray(SAMPLE_RATE / 10) // 100 ms chunks
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline) {
            val n = record.read(probe, 0, probe.size)
            for (i in 0 until n) if (probe[i].toInt() != 0) return true
        }
        return false
    }

    /**
     * Disables the platform voice-DSP effects on our capture session. We want the untouched
     * mic signal so our own offline [AudioNormalizer] can balance it; the OS effects would
     * otherwise pre-chew it — most damagingly the echo canceller, which removes the far end.
     */
    private fun disableOsEffects(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(sessionId)?.enabled = false
        }
        runCatching {
            if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(sessionId)?.enabled = false
        }
        runCatching {
            if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(sessionId)?.enabled = false
        }
    }

    /** Save first, then process. A processing failure can never erase the playable raw fallback. */
    private fun finalizeRecording(rawFile: File, pcmBytes: Long) {
        val duration = System.currentTimeMillis() - startedAt
        var rawWav: File? = null
        try {
            val rawWavFile = File(cacheDir, "raw_$startedAt.wav")
            rawWav = rawWavFile
            prependWavHeader(rawWavFile, rawFile, pcmBytes)
            rawFile.delete()

            val outDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
            val safeLabel = (displayName ?: number ?: "unknown")
                .replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
            val outWav = File(outDir, "${stamp}_$safeLabel.wav")
            val baseName = outWav.name.removeSuffix(".wav")
            val rawKept = File(outDir, "$baseName.raw.wav")
            if (!rawWavFile.renameTo(rawKept)) {
                rawWavFile.copyTo(rawKept, overwrite = true); rawWavFile.delete()
            }

            // The main playback file starts as a byte-for-byte playable raw fallback. Metadata is
            // committed immediately, before label lookup, denoising, normalization or metrics.
            rawKept.copyTo(outWav, overwrite = true)
            writeMetadata(outWav, duration)
            AppLog.i(this, "raw_recording_committed", "file=${outWav.name} bytes=$pcmBytes durationMs=$duration")

            resolveCellularLabel()
            writeMetadata(outWav, duration) // late name/number backfill without renaming the file

            if (duration <= 60_000L) {
                processShortRecording(rawKept, outWav, baseName, outDir)
            } else {
                processLongRecording(rawKept, outWav)
            }

            AppLog.i(this, "recording_saved", "file=${outWav.name} label=${displayName ?: number ?: "unknown"}")
            Log.i(TAG, "Saved recording: ${outWav.name}")
        } catch (t: Throwable) {
            // Includes OutOfMemoryError. The raw-first path normally leaves a playable recording;
            // if failure happened earlier, recover the PCM directly without allocating it.
            AppLog.e(this, "finalize_failed", "rawPcm=${rawFile.exists()} rawWav=${rawWav?.exists()}", t)
            Log.e(TAG, "Finalize failed", t)
            recoverEmergencyRecording(rawFile, rawWav, pcmBytes, duration)
        }
    }

    private fun writeMetadata(outWav: File, duration: Long) {
        val recording = Recording(
            wavPath = outWav.absolutePath,
            displayName = displayName,
            number = number,
            type = callType,
            startedAtMillis = startedAt,
            durationMillis = duration,
        )
        recording.sidecarFile().writeText(recording.toJson())
    }

    private fun processShortRecording(rawKept: File, outWav: File, baseName: String, outDir: File) {
        val loaded = WavIo.readPcm16(rawKept)
        val denoised = NoiseReducer.reduce(loaded)
        val result = AudioNormalizer.process(denoised)
        WavIo.writePcm16(outWav, result.audio)

        runCatching {
            val rawDiag = AudioNormalizer.process(loaded).diagnostics
            AudioMetrics.writeCsv(File(outDir, "$baseName.raw.csv"), loaded, rawDiag, AudioMetrics.Stage.RAW, outWav.name, usedSource)
            AudioMetrics.writeCsv(File(outDir, "$baseName.processed.csv"), result.audio, result.diagnostics, AudioMetrics.Stage.PROCESSED, outWav.name, usedSource)
        }.onFailure { AppLog.w(this, "metrics_export_failed", error = it) }
    }

    private fun processLongRecording(rawKept: File, outWav: File) {
        val processed = File(outWav.parentFile, outWav.nameWithoutExtension + ".processing.wav")
        try {
            AppLog.i(this, "chunked_processing_started", "file=${outWav.name}")
            WavIo.processMonoPcm16InChunks(rawKept, processed, SAMPLE_RATE, 15) { chunk ->
                AudioNormalizer.process(NoiseReducer.reduce(chunk)).audio
            }
            if (!processed.renameTo(outWav)) {
                processed.copyTo(outWav, overwrite = true)
                processed.delete()
            }
            AppLog.i(this, "chunked_processing_finished", "file=${outWav.name}")
        } catch (t: Throwable) {
            processed.delete()
            // outWav is still the raw fallback copied before processing.
            AppLog.e(this, "chunked_processing_failed_raw_kept", "file=${outWav.name}", t)
        }
    }

    private fun recoverEmergencyRecording(rawPcm: File, wrappedWav: File?, pcmBytes: Long, duration: Long) {
        runCatching {
            val outDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
            val outWav = File(outDir, "${stamp}_recovered.wav")
            when {
                wrappedWav?.exists() == true -> wrappedWav.copyTo(outWav, overwrite = true)
                rawPcm.exists() -> prependWavHeader(outWav, rawPcm, pcmBytes)
                else -> return
            }
            writeMetadata(outWav, duration)
            AppLog.w(this, "emergency_recording_recovered", "file=${outWav.name}")
        }.onFailure { AppLog.e(this, "emergency_recovery_failed", error = it) }
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

    /**
     * Last-resort fallback for cellular calls: if the accessibility screen-scrape didn't give us
     * both a name and a number, try the call log — waiting for THIS call's row to appear (past the
     * watermark snapshotted at call start) so we never adopt a stale row. Only fills blanks, so a
     * good scraped label always wins. No-op for WhatsApp and when we already have a full label.
     * Runs on the record thread, so the blocking poll never touches the main thread.
     */
    private fun resolveCellularLabel() {
        if (callType != CallType.CELLULAR && !(callType == CallType.UNKNOWN && manuallyStarted)) return
        // Stopped by hand mid-call: this call's row only lands in the log when the call ENDS, so
        // the poll could not possibly find it and would just burn its timeout. Keep the scrape.
        if (!lastStopWasCallEnd && (reArmAfterStop || callType == CallType.UNKNOWN)) return
        if (!displayName.isNullOrBlank() && !number.isNullOrBlank()) return  // screen already gave us both
        val entry = com.callstream.recorder.call.CallLogResolver
            .resolveFinishedCall(applicationContext, logWatermark, startedAt, System.currentTimeMillis()) ?: run {
                AppLog.w(this, "cellular_label_not_found", "watermark=$logWatermark")
                return
            }
        if (callType == CallType.UNKNOWN) callType = CallType.CELLULAR
        if (number.isNullOrBlank()) entry.number?.let { number = it }
        if (displayName.isNullOrBlank()) {
            val name = com.callstream.recorder.call.ContactResolver
                .nameForNumber(applicationContext, entry.number ?: number) ?: entry.cachedName
            if (!name.isNullOrBlank()) displayName = name
        }
        AppLog.i(this, "cellular_label_resolved", "name=${displayName ?: "missing"} number=${number ?: "missing"}")
    }

    /**
     * Posts the end of a capture to the main thread, once the record thread has fully finalized.
     * A user-initiated mid-call stop returns us to ARMED (the call is still up — they may want to
     * record another stretch of it); anything else retires the service.
     */
    private fun finishService() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            recordThread = null
            if (reArmAfterStop) {
                reArmAfterStop = false
                usedSource = "unknown"
                displayName = null
                number = null
                callType = CallType.UNKNOWN
                logWatermark = 0L
                manuallyStarted = false
                lastStopWasCallEnd = false
                state = State.ARMED
                startForegroundWithNotif()  // repaints the button back to "record"
            } else {
                state = null
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        recording = false
        state = null
        removeOverlay()
        AppLog.w(this, "recording_service_destroyed")
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
        val capturing = state == State.RECORDING
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(if (capturing) R.string.recording_in_progress else R.string.recording_armed))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            // The same control as the floating button, but reachable even when the "draw over
            // other apps" permission is denied — otherwise the overlay is the only way in.
            .addAction(
                if (capturing) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now,
                getString(if (capturing) R.string.action_stop_recording else R.string.action_start_recording),
                selfIntent(if (capturing) ACTION_STOP else ACTION_START, reArm = capturing)
            )
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
        AppLog.i(this, "controls_presented", "state=$state overlay=${overlay != null}")
    }

    /** A PendingIntent that sends [action] back to this service, for the notification buttons. */
    private fun selfIntent(action: String, reArm: Boolean): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),  // distinct request codes, so start and stop stay distinct PIs
            Intent(this, CallRecordingService::class.java).also {
                it.action = action
                if (reArm) it.putExtra(EXTRA_REARM, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ---- Floating overlay --------------------------------------------------------------

    private fun showOverlay() {
        if (overlay == null) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                AppLog.w(this, "overlay_unavailable", "permission=false; notification_fallback=true")
                return
            }
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
            try {
                windowManager?.addView(view, params)
                overlay = view
                AppLog.i(this, "overlay_added")
            } catch (t: Throwable) {
                AppLog.e(this, "overlay_add_failed", error = t)
                overlay = null
                return
            }
        }
        renderOverlay()
    }

    /**
     * Paints the floating widget for the current state and wires its buttons. ARMED offers a
     * record button that starts capturing mid-call; while RECORDING the same button becomes
     * stop-and-save (and we come back ARMED, so the call can be recorded in several stretches).
     */
    private fun renderOverlay() {
        val view = overlay ?: return
        val capturing = state == State.RECORDING
        view.findViewById<TextView>(R.id.txt_status)?.text =
            getString(if (capturing) R.string.recording_in_progress else R.string.tap_to_record)
        view.findViewById<ImageView>(R.id.btn_record)?.apply {
            setImageResource(
                if (capturing) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now
            )
            setColorFilter(if (capturing) COLOR_RECORDING else COLOR_IDLE)
            setOnClickListener {
                if (state == State.RECORDING) sendSelf(ACTION_STOP, reArm = true)
                else sendSelf(ACTION_START, reArm = false)
            }
        }
        // X only hides this floating view. It deliberately does not stop, save or alter capture.
        // Recording controls remain available in the foreground notification and Quick Settings.
        view.findViewById<ImageView>(R.id.btn_close)?.setOnClickListener {
            sendSelf(ACTION_HIDE_OVERLAY, reArm = false)
        }
    }

    private fun sendSelf(action: String, reArm: Boolean) {
        val i = Intent(this, CallRecordingService::class.java).also {
            it.action = action
            if (reArm) it.putExtra(EXTRA_REARM, true)
        }
        runCatching { startService(i) }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
    }
}
