package com.callstream.recorder.service

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.callstream.recorder.RecorderPreferences
import com.callstream.recorder.call.CallType
import com.callstream.recorder.diagnostics.AppLog

/** WhatsApp-call/name fallback based on its ongoing call notification. */
class WhatsAppNotificationService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isWhatsApp(sbn.packageName) || !looksLikeCall(sbn)) return
        handler.removeCallbacksAndMessages(null)
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() && !it.equals("WhatsApp", true) }
        val action = if (RecorderPreferences.shouldRecord(this, CallType.WHATSAPP)) {
            CallRecordingService.ACTION_START
        } else CallRecordingService.ACTION_ARM
        AppLog.i(this, "whatsapp_notification_call", "title=${title ?: "missing"} action=$action")
        val i = Intent(this, CallRecordingService::class.java).apply {
            this.action = action
            putExtra(CallRecordingService.EXTRA_TYPE, CallType.WHATSAPP.name)
            title?.let { putExtra(CallRecordingService.EXTRA_NAME, it) }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }.onFailure { AppLog.e(this, "whatsapp_notification_start_failed", error = it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isWhatsApp(sbn.packageName) || !looksLikeCall(sbn)) return
        handler.postDelayed({
            val stillPresent = activeNotifications.orEmpty().any { isWhatsApp(it.packageName) && looksLikeCall(it) }
            if (!stillPresent) {
                AppLog.i(this, "whatsapp_notification_ended")
                startService(Intent(this, CallRecordingService::class.java).apply {
                    action = CallRecordingService.ACTION_STOP
                    putExtra(CallRecordingService.EXTRA_CALL_ENDED, true)
                })
            }
        }, 4_000L)
    }

    private fun isWhatsApp(pkg: String) = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    private fun looksLikeCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val text = listOf(
            n.extras.getCharSequence(Notification.EXTRA_TITLE),
            n.extras.getCharSequence(Notification.EXTRA_TEXT),
            n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).joinToString(" ").lowercase()
        return n.category == Notification.CATEGORY_CALL ||
            (n.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
                listOf("call", "calling", "ongoing", "שיחה", "מתקשר").any(text::contains))
    }
}
