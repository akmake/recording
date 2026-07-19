package com.callstream.recorder.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.callstream.recorder.call.CallLogResolver
import com.callstream.recorder.call.CallType
import com.callstream.recorder.call.ContactResolver
import com.callstream.recorder.RecorderPreferences
import com.callstream.recorder.diagnostics.AppLog

/**
 * Always-on detector. Two independent triggers:
 *
 *   • Cellular calls — start/stop via the telephony call-state callback. The name + number are
 *     read straight off the system dialer's in-call screen through accessibility (the same info
 *     the user sees), which needs no READ_CALL_LOG and is correct even while ringing. The call
 *     log is only a last-resort fallback in [CallRecordingService].
 *   • WhatsApp/VoIP calls — detected via accessibility window events, since these never touch
 *     the telephony stack. The contact name is scraped from the call screen (no dialable number).
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
    // True from the moment a cellular call rings/starts until it ends. Gates the in-call screen
    // scraping so we don't run expensive tree searches on every window event of every app.
    @Volatile private var phoneCallPresent = false
    private var cellularLogWatermark = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pendingWhatsAppStop: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i(this, "accessibility_connected")
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
            AppLog.e(this, "phone_listener_registration_failed", error = e)
        }
    }

    private var lastNumber: String? = null

    private fun handleCellularState(state: Int, number: String?) {
        if (!number.isNullOrBlank()) lastNumber = number
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call ringing — enable scraping so we grab the caller off the ring
                // screen before it's even answered. Recording itself waits for OFFHOOK.
                phoneCallPresent = true
                cellularLogWatermark = CallLogResolver.newestId(this)
                AppLog.i(this, "cellular_ringing", "source=telephony watermark=$cellularLogWatermark")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                phoneCallPresent = true
                if (!cellularActive && !whatsAppActive) {
                    cellularActive = true
                    // Prefer the label scraped off the in-call screen (works without READ_CALL_LOG
                    // and is already correct while ringing). Fall back to the legacy phone-state
                    // number on Android < 12. Snapshot the call-log watermark too, so the recorder
                    // can use the log as a last resort for exactly this call's row.
                    if (cellularLogWatermark == 0L) cellularLogWatermark = CallLogResolver.newestId(this)
                    val name = pendingName ?: ContactResolver.nameForNumber(this, lastNumber)
                    val number = pendingNumber ?: lastNumber
                    onCallStarted(name, number, CallType.CELLULAR, cellularLogWatermark)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                phoneCallPresent = false
                lastNumber = null
                pendingName = null
                pendingNumber = null  // clear even for missed/declined calls (RINGING→IDLE)
                if (cellularActive) {
                    cellularActive = false
                    // The screen scrape (pushed live via UPDATE_LABEL) is the authoritative label;
                    // the recorder falls back to the call log at finalize only if it's still blank.
                    stopRecording()
                }
                cellularLogWatermark = 0L
            }
        }
    }

    // ---- Screen scraping (WhatsApp/VoIP + cellular in-call UI) --------------------------

    // Best label scraped off the cellular in-call screen for the current call. This is the
    // primary, permission-free source of the name/number (the call log is only a fallback).
    @Volatile private var pendingName: String? = null
    @Volatile private var pendingNumber: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return  // ignore our own windows
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (pkg == WHATSAPP_PKG || pkg == WHATSAPP_BIZ_PKG) handleWhatsAppEvent()
        else handlePhoneScreenEvent()
    }

    private fun handleWhatsAppEvent() {
        val root = rootInActiveWindow ?: return
        val onCallScreen = looksLikeCallScreen(root)
        if (onCallScreen) {
            pendingWhatsAppStop?.let(handler::removeCallbacks)
            pendingWhatsAppStop = null
            val name = extractWhatsAppName(root)
            if (!whatsAppActive && !cellularActive) {
                whatsAppActive = true
                onCallStarted(name, null, CallType.WHATSAPP)
            } else if (whatsAppActive && !name.isNullOrBlank()) {
                sendLabelUpdate(name, null)
            }
        } else if (!onCallScreen && whatsAppActive) {
            // A minimized call or a transient WhatsApp window is not an ended call. Wait and
            // re-check instead of dropping the recording on the first missing control label.
            pendingWhatsAppStop?.let(handler::removeCallbacks)
            pendingWhatsAppStop = Runnable {
                val stillCall = rootInActiveWindow?.let(::looksLikeCallScreen) == true
                if (!stillCall && whatsAppActive) {
                    whatsAppActive = false
                    AppLog.i(this, "whatsapp_accessibility_ended", "after_debounce=true")
                    stopRecording()
                }
                pendingWhatsAppStop = null
            }.also { handler.postDelayed(it, 5_000L) }
        }
    }

    /**
     * The out-of-the-box path: read the caller's name/number straight off the system dialer's
     * in-call screen — exactly what the user sees — instead of the restricted call log. Works for
     * any dialer package (Google/Samsung/AOSP/…) because we key off call-control markers on screen
     * rather than a hard-coded package list, and it captures the label even while the phone is
     * still ringing (before OFFHOOK). The recorder keeps the best label we push it.
     */
    private fun handlePhoneScreenEvent() {
        if (!phoneCallPresent) return  // only scrape while a cellular call is ringing/active
        val root = rootInActiveWindow ?: return
        if (!looksLikeCallScreen(root)) return
        val (name, number) = extractPhoneLabel(root)
        var changed = false
        if (!name.isNullOrBlank() && name != pendingName) { pendingName = name; changed = true }
        if (!number.isNullOrBlank() && number != pendingNumber) { pendingNumber = number; changed = true }
        // Push only when something actually changed (content-changed events fire constantly).
        // If the call isn't recording yet, OFFHOOK will pick up `pending*` when it starts.
        if (changed && cellularActive) sendLabelUpdate(pendingName, pendingNumber)
    }

    /**
     * Heuristic: any active-call screen (WhatsApp OR the system dialer, incoming or in-call)
     * exposes call-control text. Broad on purpose so it fires across OEM dialers and both
     * languages; findNodeByText matches substrings, so short markers catch longer labels.
     */
    private fun looksLikeCallScreen(root: AccessibilityNodeInfo): Boolean {
        val markers = listOf(
            "End call", "סיום שיחה", "Mute", "השתקה", "Speaker", "רמקול",
            "Video call", "שיחת וידאו", "Keypad", "לוח מקשים", "Hold", "החזקה",
            "Add call", "הוסף שיחה", "Answer", "מענה", "Decline", "דחייה", "Bluetooth"
        )
        return markers.any { findNodeByText(root, it) != null }
    }

    // ---- Cellular in-call screen scraping ----------------------------------------------

    private data class ScreenText(val text: String, val top: Int)

    // Button / status / label texts that are never the contact name. Matched case-insensitively
    // and exactly, so real names are not discarded; anything that slips through still loses to the
    // topmost-text rule below (buttons sit at the bottom, the name at the top).
    private val NON_NAME_TEXTS = setOf(
        "mute", "השתקה", "speaker", "רמקול", "keypad", "לוח מקשים", "add call", "add", "הוסף שיחה",
        "הוספה", "hold", "החזקה", "החזק", "bluetooth", "video", "וידאו", "video call", "שיחת וידאו",
        "end call", "סיום שיחה", "end", "סיום", "hang up", "answer", "מענה", "ענה", "decline",
        "דחייה", "דחה", "reject", "message", "הודעה", "record", "הקלטה", "merge", "מזג", "contacts",
        "אנשי קשר", "more", "עוד", "facetime", "calling", "מתקשר", "מחייג", "dialing", "ringing",
        "מצלצל", "connecting", "מתחבר", "call ended", "השיחה הסתיימה", "incoming call", "שיחה נכנסת",
        "mobile", "נייד", "home", "בית", "work", "עבודה", "on hold", "בהמתנה"
    )

    /**
     * Pulls the caller name and/or number straight from a system in-call screen. The number is the
     * first phone-number-shaped string; the name is the TOPMOST text that has letters and isn't a
     * control/status/timer word (the contact name sits at the top; buttons at the bottom). Unknown
     * caller → no name, and the number becomes the label.
     */
    private fun extractPhoneLabel(root: AccessibilityNodeInfo): Pair<String?, String?> {
        val nodes = ArrayList<ScreenText>()
        collectTextNodes(root, nodes)
        var number: String? = null
        var name: String? = null
        var nameTop = Int.MAX_VALUE
        for (n in nodes) {
            val t = n.text
            if (t.isBlank() || t.lowercase() in NON_NAME_TEXTS || isCallTimer(t)) continue
            if (looksLikeNumber(t)) {
                if (number == null) number = normalizeNumber(t)
                continue
            }
            if (t.length in 2..40 && t.any { it.isLetter() } && n.top < nameTop) {
                name = t
                nameTop = n.top
            }
        }
        return name to number
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, out: MutableList<ScreenText>) {
        node ?: return
        // Some OEM dialers expose the contact name only via contentDescription, not text.
        val label = node.text?.toString()?.trim()?.ifBlank { null }
            ?: node.contentDescription?.toString()?.trim()?.ifBlank { null }
        if (label != null) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            out.add(ScreenText(label, r.top))
        }
        for (i in 0 until node.childCount) collectTextNodes(node.getChild(i), out)
    }

    /** A phone-number-shaped string: mostly digits, plus the usual separators. */
    private fun looksLikeNumber(s: String): Boolean =
        s.count { it.isDigit() } >= 5 && s.all { it.isDigit() || it in "+()-. #*‎‏" }

    /** Keep only dialable characters, so a saved-contact lookup on it can still succeed. */
    private fun normalizeNumber(s: String): String = s.filter { it.isDigit() || it == '+' }

    /** The live call duration ("0:42", "1:03:20") — never a name or number. */
    private fun isCallTimer(s: String): Boolean = s.matches(Regex("\\d{1,2}(:\\d{2}){1,2}"))

    private fun sendLabelUpdate(name: String?, number: String?) {
        if (name.isNullOrBlank() && number.isNullOrBlank()) return
        val i = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_UPDATE_LABEL
            name?.let { putExtra(CallRecordingService.EXTRA_NAME, it) }
            number?.let { putExtra(CallRecordingService.EXTRA_NUMBER, it) }
        }
        try { startService(i) } catch (_: Exception) {}
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
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it.trim()) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out)
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    // ---- Bridge to recorder ------------------------------------------------------------

    /**
     * Brings [CallRecordingService] up for a call that just started. When the user's preferences
     * don't auto-record this call we still bring it up — ARMED, capturing nothing — because that
     * service is what owns the on-screen record button. Bailing out here instead (the old
     * behavior) left the user with no UI whatsoever during the call, so recording by hand was
     * impossible: the only button lived in a service that never ran.
     */
    private fun onCallStarted(name: String?, number: String?, type: CallType, logWatermark: Long = 0L) {
        val action = if (RecorderPreferences.shouldRecord(this, type)) {
            CallRecordingService.ACTION_START
        } else {
            CallRecordingService.ACTION_ARM
        }
        Log.i(TAG, "Call started: type=$type name=$name number=$number action=$action")
        AppLog.i(this, "call_detected", "source=accessibility type=$type name=${name ?: "missing"} number=${number ?: "missing"} action=$action")
        val i = Intent(this, CallRecordingService::class.java).apply {
            this.action = action
            putExtra(CallRecordingService.EXTRA_NAME, name)
            putExtra(CallRecordingService.EXTRA_NUMBER, number)
            putExtra(CallRecordingService.EXTRA_TYPE, type.name)
            putExtra(CallRecordingService.EXTRA_LOG_WATERMARK, logWatermark)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }.onFailure { AppLog.e(this, "recorder_start_failed", "type=$type", it) }
    }

    private fun stopRecording(name: String? = null, number: String? = null) {
        Log.i(TAG, "Call ended (name=$name number=$number)")
        val i = Intent(this, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP
            putExtra(CallRecordingService.EXTRA_CALL_ENDED, true)
            name?.let { putExtra(CallRecordingService.EXTRA_NAME, it) }
            number?.let { putExtra(CallRecordingService.EXTRA_NUMBER, it) }
        }
        runCatching { startService(i) }.onFailure { AppLog.e(this, "recorder_stop_failed", error = it) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        pendingWhatsAppStop?.let(handler::removeCallbacks)
        AppLog.w(this, "accessibility_destroyed")
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
