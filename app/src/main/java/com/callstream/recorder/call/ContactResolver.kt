package com.callstream.recorder.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Resolves a phone number to a saved contact's display name via the Contacts provider.
 *
 * Requires READ_CONTACTS. For cellular calls the number comes from the phone-state
 * listener (needs READ_CALL_LOG to be non-empty on Android 9+). For WhatsApp/VoIP
 * calls there is no number at all — the name is scraped from the call UI by
 * [CallAccessibilityService] instead, and passed straight through.
 */
object ContactResolver {

    /** @return the contact display name, or null if unknown / permission missing. */
    fun nameForNumber(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** Builds the human label shown in the UI and used in the filename. */
    fun label(name: String?, number: String?): String = when {
        !name.isNullOrBlank() -> name
        !number.isNullOrBlank() -> number
        else -> "Unknown"
    }
}
