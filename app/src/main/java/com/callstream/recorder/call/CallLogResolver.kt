package com.callstream.recorder.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/**
 * Recovers a cellular call's phone number (and any caller-ID name) from the call log.
 *
 * Needed because on Android 12+ the modern `TelephonyCallback.onCallStateChanged(state)` no
 * longer carries the number — so without reading the call log, cellular recordings have no
 * number to resolve a contact from at all (they end up labeled "unknown").
 *
 * `CACHED_NAME` is Android's own caller-ID cache: it can hold a name even for a number that is
 * NOT saved in the user's contacts (supplied by the carrier / caller-ID services).
 *
 * Read this at call END — by then the row for the just-finished call is reliably the newest
 * entry, so we never mistake it for a previous call.
 */
object CallLogResolver {

    data class Entry(val number: String?, val cachedName: String?)

    /** Newest call-log row, or null if none / permission missing. */
    fun mostRecent(context: Context): Entry? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) Entry(
                    number = c.getString(0)?.takeIf { it.isNotBlank() },
                    cachedName = c.getString(1)?.takeIf { it.isNotBlank() },
                ) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
