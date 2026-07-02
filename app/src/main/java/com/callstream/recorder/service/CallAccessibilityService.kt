package com.callstream.recorder.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.ContactResolver

/**
 * Always-on detector. Two independent triggers:
 *
 *   • Cellular calls — via the telephony call-state callback. The number (when the OS
 *     provides it, i.e. READ_CALL_LOG granted) is resolved to a contact name.
 *   • WhatsApp/VoIP calls — via accessibility window events, since these never touch the
 *     telephony stack. The contact name is scraped from the call screen and passed
 *     straight through (there is no dialable number to resolve).
 *
 * Both funnel into [CallRecordingService], which records and normalizes.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAccessibility"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BIZ_PKG = "com.whatsapp.w4b"
    }

    private var telephonyManager: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    @Volatile private var cellularActive = false
    @Volatile private var whatsAppActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerPhoneState()
    }

    // ---- Cellular ----------------------------------------------------------------------

    private fun registerPhoneState() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleCellularState(state, null)
                }
                modernCallback = cb
                telephonyManager?.registerTelephonyCallback(mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleCellularState(state, phoneNumber)
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register phone-state listener", e)
        }
    }

    private var lastNumber: String? = null

    private fun handleCellularState(state: Int, number: String?) {
        if (!number.isNullOrBlank()) lastNumber = number
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!cellularActive && !whatsAppActive) {
                    cellularActive = true
                    val name = ContactResolver.nameForNumber(this, lastNumber)
                    startRecording(name, lastNumber, CallType.CELLULAR)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (cellularActive) {
                    cellularActive = false
                    lastNumber = null
                    stopRecording()
                }
            }
        }
    }

    // ---- WhatsApp / VoIP ---------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BIZ_PKG) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val root = rootInActiveWindow ?: return
        val onCallScreen = looksLikeCallScreen(root)

        if (onCallScreen && !whatsAppActive && !cellularActive) {
            whatsAppActive = true
            val name = extractWhatsAppName(root)
            startRecording(name, null, CallType.WHATSAPP)
        } else if (!onCallScreen && whatsAppActive) {
            whatsAppActive = false
            stopRecording()
        }
    }

    /** Heuristic: a WhatsApp call screen exposes call-control text ("End call" / "משתמש בהמתנה" etc.). */
    private fun looksLikeCallScreen(root: AccessibilityNodeInfo): Boolean {
        val markers = listOf("End call", "סיום שיחה", "Mute", "השתקה", "Speaker", "רמקול", "Video call", "שיחת וידאו")
        return markers.any { findNodeByText(root, it) != null }
    }

    /**
     * The largest prominent text on a WhatsApp call screen is the contact name (the call
     * duration and control labels are the other texts). We pick the first non-control,
     * non-numeric text node near the top.
     */
    private fun extractWhatsAppName(root: AccessibilityNodeInfo): String? {
        val controls = setOf(
            "End call", "סיום שיחה", "Mute", "השתקה", "Speaker", "רמקול",
            "Add", "הוספה", "Video", "וידאו", "Minimize", "מזעור"
        )
        val texts = ArrayList<String>()
        collectTexts(root, texts)
        return texts.firstOrNull { t ->
            t.isNotBlank() &&
                t !in controls &&
                !t.matches(Regex("[0-9:]+")) &&      // skip call timer
                t.length in 2..40
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it.trim()) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out)
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    // ---- Bridge to recorder ------------------------------------------------------------

    private fun startRecording(name: String?, number: String?, type: CallType) {
        Log.i(TAG, "Call started: type=$type name=$name number=$number")
        val i = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START
            putExtra(CallRecordingService.EXTRA_NAME, name)
            putExtra(CallRecordingService.EXTRA_NUMBER, number)
            putExtra(CallRecordingService.EXTRA_TYPE, type.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun stopRecording() {
        Log.i(TAG, "Call ended")
        val i = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP
        }
        startService(i)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
