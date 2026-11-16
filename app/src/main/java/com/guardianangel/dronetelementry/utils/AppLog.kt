package com.guardianangel.dronetelementry.utils

import android.util.Log

/**
 * Central Logcat helper. Filter by tag prefix `DroneTel/` in Logcat.
 */
object AppLog {
    private const val PREFIX = "DroneTel"

    fun d(tag: String, message: String) {
        Log.d("$PREFIX/$tag", message)
    }

    fun i(tag: String, message: String) {
        Log.i("$PREFIX/$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w("$PREFIX/$tag", message, throwable)
        else Log.w("$PREFIX/$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e("$PREFIX/$tag", message, throwable)
        else Log.e("$PREFIX/$tag", message)
    }

    /**
     * Logs [message] in full. Logcat cuts a single entry at ~4 KB, so long payloads are split
     * into numbered parts that share the same tag.
     */
    fun full(tag: String, message: String) {
        if (message.length <= MAX_ENTRY) {
            Log.d("$PREFIX/$tag", message)
            return
        }
        val parts = (message.length + MAX_ENTRY - 1) / MAX_ENTRY
        for (i in 0 until parts) {
            val chunk = message.substring(i * MAX_ENTRY, minOf(message.length, (i + 1) * MAX_ENTRY))
            Log.d("$PREFIX/$tag", "[${i + 1}/$parts] $chunk")
        }
    }

    private const val MAX_ENTRY = 3800

    /** Truncate secrets for logs (tokens, tickets). */
    fun redact(value: String?, keepStart: Int = 6, keepEnd: Int = 4): String {
        if (value.isNullOrBlank()) return "<empty>"
        if (value.length <= keepStart + keepEnd) return "***"
        return value.take(keepStart) + "…" + value.takeLast(keepEnd) + " (len=${value.length})"
    }
}
