package com.callstream.recorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.callstream.recorder.RecorderPreferences
import com.callstream.recorder.call.CallLogResolver
import com.callstream.recorder.call.CallType
import com.callstream.recorder.diagnostics.AppLog

/** Independent cellular-call backup; works even when the accessibility service misses an event. */
class CellularCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val prefs = context.getSharedPreferences("cellular_call_backup", Context.MODE_PRIVATE)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                prefs.edit().putLong("watermark", CallLogResolver.newestId(context)).apply()
                AppLog.i(context, "cellular_ringing", "source=broadcast")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (prefs.getBoolean("active", false)) return
                prefs.edit().putBoolean("active", true).apply()
                val action = if (RecorderPreferences.shouldRecord(context, CallType.CELLULAR)) {
                    CallRecordingService.ACTION_START
                } else CallRecordingService.ACTION_ARM
                val service = Intent(context, CallRecordingService::class.java).apply {
                    this.action = action
                    putExtra(CallRecordingService.EXTRA_TYPE, CallType.CELLULAR.name)
                    putExtra(CallRecordingService.EXTRA_LOG_WATERMARK, prefs.getLong("watermark", 0L))
                }
                AppLog.i(context, "cellular_started", "source=broadcast action=$action")
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
                    else context.startService(service)
                }.onFailure { AppLog.e(context, "cellular_service_start_failed", error = it) }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val wasActive = prefs.getBoolean("active", false)
                prefs.edit().putBoolean("active", false).apply()
                if (!wasActive) return
                AppLog.i(context, "cellular_ended", "source=broadcast")
                val stop = Intent(context, CallRecordingService::class.java).apply {
                    action = CallRecordingService.ACTION_STOP
                    putExtra(CallRecordingService.EXTRA_CALL_ENDED, true)
                }
                runCatching { context.startService(stop) }
                    .onFailure { AppLog.e(context, "cellular_stop_failed", error = it) }
            }
        }
    }
}
