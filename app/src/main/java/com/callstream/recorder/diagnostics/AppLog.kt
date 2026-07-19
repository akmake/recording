package com.callstream.recorder.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent, rotating diagnostic journal that survives app/process restarts. */
object AppLog {
    private const val TAG = "Reko"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_BYTES = 256 * 1024
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(context: Context): File =
        File(context.filesDir, "logs/reko.log").apply { parentFile?.mkdirs() }

    fun i(context: Context, event: String, details: String = "") =
        write(context, "I", event, details, null)

    fun w(context: Context, event: String, details: String = "", error: Throwable? = null) =
        write(context, "W", event, details, error)

    fun e(context: Context, event: String, details: String = "", error: Throwable? = null) =
        write(context, "E", event, details, error)

    private fun write(context: Context, level: String, event: String, details: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e(TAG, "$event $details", error)
            "W" -> Log.w(TAG, "$event $details", error)
            else -> Log.i(TAG, "$event $details")
        }
        synchronized(lock) {
            runCatching {
                val suffix = buildString {
                    if (details.isNotBlank()) append(" | ").append(details.replace('\n', ' '))
                    if (error != null) append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message)
                }
                val line = "${formatter.format(Date())} $level $event$suffix\n"
                val target = file(context.applicationContext)
                if (target.length() > MAX_BYTES) {
                    val bytes = target.readBytes()
                    val start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
                    target.writeBytes(bytes.copyOfRange(start, bytes.size))
                }
                target.appendText(line)
            }
        }
    }

    fun tail(context: Context, maxLines: Int = 200): String = synchronized(lock) {
        runCatching { file(context).readLines().takeLast(maxLines).joinToString("\n") }
            .getOrDefault("")
    }

    fun clear(context: Context) = synchronized(lock) { runCatching { file(context).writeText("") } }
}
