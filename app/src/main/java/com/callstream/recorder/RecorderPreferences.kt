package com.callstream.recorder

import android.content.Context
import com.callstream.recorder.call.CallType

enum class RecordingMode { BOTH, CELLULAR, WHATSAPP }

object RecorderPreferences {
    private const val STORE = "recorder_preferences"
    private const val AUTO_RECORD = "auto_record"
    private const val RECORDING_MODE = "recording_mode"

    fun isAutoRecordEnabled(context: Context): Boolean =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(AUTO_RECORD, true)

    fun setAutoRecordEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_RECORD, enabled).apply()
    }

    fun recordingMode(context: Context): RecordingMode = runCatching {
        RecordingMode.valueOf(
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(RECORDING_MODE, RecordingMode.BOTH.name)!!
        )
    }.getOrDefault(RecordingMode.BOTH)

    fun setRecordingMode(context: Context, mode: RecordingMode) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(RECORDING_MODE, mode.name).apply()
    }

    fun shouldRecord(context: Context, type: CallType): Boolean {
        if (!isAutoRecordEnabled(context)) return false
        return when (recordingMode(context)) {
            RecordingMode.BOTH -> type == CallType.CELLULAR || type == CallType.WHATSAPP
            RecordingMode.CELLULAR -> type == CallType.CELLULAR
            RecordingMode.WHATSAPP -> type == CallType.WHATSAPP
        }
    }
}
