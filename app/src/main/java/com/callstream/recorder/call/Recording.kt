package com.callstream.recorder.call

import org.json.JSONObject
import java.io.File

/**
 * Metadata for one recorded call. Persisted as a small ".json" sidecar next to the
 * ".wav" so the recordings list can show who the call was with, when, and how long —
 * without re-querying Contacts or parsing filenames.
 */
data class Recording(
    val wavPath: String,
    val displayName: String?,
    val number: String?,
    val type: CallType,
    val startedAtMillis: Long,
    val durationMillis: Long,
) {
    val file: File get() = File(wavPath)
    val label: String get() = ContactResolver.label(displayName, number)

    fun toJson(): String = JSONObject().apply {
        put("wavPath", wavPath)
        put("displayName", displayName ?: JSONObject.NULL)
        put("number", number ?: JSONObject.NULL)
        put("type", type.name)
        put("startedAtMillis", startedAtMillis)
        put("durationMillis", durationMillis)
    }.toString()

    fun sidecarFile(): File = File(wavPath.removeSuffix(".wav") + ".json")

    companion object {
        fun fromSidecar(json: File): Recording? = try {
            val o = JSONObject(json.readText())
            Recording(
                wavPath = o.getString("wavPath"),
                displayName = if (o.isNull("displayName")) null else o.getString("displayName").ifBlank { null },
                number = if (o.isNull("number")) null else o.getString("number").ifBlank { null },
                type = runCatching { CallType.valueOf(o.getString("type")) }.getOrDefault(CallType.CELLULAR),
                startedAtMillis = o.getLong("startedAtMillis"),
                durationMillis = o.getLong("durationMillis"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

enum class CallType { CELLULAR, WHATSAPP, UNKNOWN }
