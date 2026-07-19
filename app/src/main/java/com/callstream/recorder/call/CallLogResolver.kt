package com.callstream.recorder.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
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
 * ## Why the watermark + poll (the whole point of this class)
 *
 * The row for the just-finished call is written to the log *asynchronously* — typically a few
 * hundred ms to a couple of seconds AFTER `CALL_STATE_IDLE` fires. Reading "the newest row"
 * at the instant the call ends therefore returns the PREVIOUS call's row (wrong name/number)
 * or nothing at all. And we can't just match by "row date >= call start", because for an
 * incoming call the log DATE is the ring-start, which can precede the answer by many seconds.
 *
 * Instead: at call START we snapshot [newestDate] (the "watermark" = date of whatever the
 * newest row is *before* this call is logged). At call END we [resolveAfter] that watermark —
 * polling until the first row strictly newer than the watermark appears. That row is
 * unambiguously the call we just recorded, regardless of ring duration or direction.
 */
object CallLogResolver {

    data class Entry(
        val id: Long,
        val number: String?,
        val cachedName: String?,
        val date: Long,
        val durationSeconds: Long = 0L,
    )

    private const val DEFAULT_TIMEOUT_MS = 6_000L
    private const val DEFAULT_INTERVAL_MS = 400L

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Newest call-log row, or null if none / permission missing. */
    private fun newest(context: Context): Entry? {
        if (!hasPermission(context)) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) Entry(
                    id = c.getLong(0),
                    number = c.getString(1)?.takeIf { it.isNotBlank() },
                    cachedName = c.getString(2)?.takeIf { it.isNotBlank() },
                    date = c.getLong(3),
                    durationSeconds = c.getLong(4),
                ) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Date of the newest call-log row right now, or 0 if none / no permission. Snapshot this at
     * call START and hand it to [resolveAfter] at call END as the watermark. 0 is a safe floor:
     * if the user has no prior calls, the very first row that appears will still be newer.
     */
    fun newestDate(context: Context): Long = newest(context)?.date ?: 0L

    /** Monotonic call-log row watermark; safer than DATE on ROMs that insert at ring/start time. */
    fun newestId(context: Context): Long = newest(context)?.id ?: 0L

    /**
     * Blocks (call OFF the main thread) polling the log until a row strictly newer than
     * [afterDate] with a usable number appears, then returns it. Returns null if nothing
     * newer arrives within [timeoutMs] — the caller should then keep whatever label it
     * already had rather than attribute a stale row.
     *
     * A newer row that carries a date but a blank number (some ROMs write the row in two
     * steps) is remembered and returned at timeout, so we at least advance past the stale row.
     */
    fun resolveAfter(
        context: Context,
        afterDate: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Entry? {
        if (!hasPermission(context)) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var newerButBlank: Entry? = null
        while (true) {
            val e = newest(context)
            if (e != null && e.date > afterDate) {
                if (!e.number.isNullOrBlank()) return e   // the row we want, fully populated
                newerButBlank = e                         // right call, number not written yet
            }
            if (SystemClock.elapsedRealtime() >= deadline) return newerButBlank
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return newerButBlank
            }
        }
    }

    /** Resolve the first row inserted after [afterId], regardless of the timestamp chosen by the ROM. */
    fun resolveAfterId(
        context: Context,
        afterId: Long,
        timeoutMs: Long = 20_000L,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Entry? {
        if (!hasPermission(context)) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var newerButBlank: Entry? = null
        while (true) {
            val e = newest(context)
            if (e != null && e.id > afterId) {
                if (!e.number.isNullOrBlank()) return e
                newerButBlank = e
            }
            if (SystemClock.elapsedRealtime() >= deadline) return newerButBlank
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return newerButBlank }
        }
    }

    /**
     * Timing-based fallback for ROMs that insert the current row before OFFHOOK (so its id is not
     * newer than our watermark) or only several seconds after IDLE. It scores recent rows by both
     * estimated end time and duration, and refuses candidates that are too far from this capture.
     */
    fun resolveFinishedCall(
        context: Context,
        afterId: Long,
        recordingStartedAt: Long,
        recordingEndedAt: Long,
        timeoutMs: Long = 20_000L,
    ): Entry? {
        if (!hasPermission(context)) return null
        val recordingDuration = (recordingEndedAt - recordingStartedAt).coerceAtLeast(0L)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val candidates = recent(context)
            val best = candidates.mapNotNull { entry ->
                val estimatedEnd = entry.date + entry.durationSeconds * 1000L
                val endDiff = kotlin.math.abs(estimatedEnd - recordingEndedAt)
                val durationDiff = kotlin.math.abs(entry.durationSeconds * 1000L - recordingDuration)
                val insertedAfterStart = entry.id > afterId
                val withinWindow = entry.date <= recordingEndedAt + 30_000L &&
                    entry.date >= recordingStartedAt - 5 * 60_000L &&
                    endDiff <= 3 * 60_000L && durationDiff <= 3 * 60_000L
                if (!withinWindow) null else entry to (endDiff + durationDiff + if (insertedAfterStart) 0L else 30_000L)
            }.minByOrNull { it.second }?.first
            if (best != null && !best.number.isNullOrBlank()) return best
            if (SystemClock.elapsedRealtime() >= deadline) return best
            try { Thread.sleep(DEFAULT_INTERVAL_MS) } catch (_: InterruptedException) { return best }
        }
    }

    private fun recent(context: Context): List<Entry> {
        if (!hasPermission(context)) return emptyList()
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < 12) add(Entry(
                        id = c.getLong(0),
                        number = c.getString(1)?.takeIf { it.isNotBlank() },
                        cachedName = c.getString(2)?.takeIf { it.isNotBlank() },
                        date = c.getLong(3),
                        durationSeconds = c.getLong(4),
                    ))
                }
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}
