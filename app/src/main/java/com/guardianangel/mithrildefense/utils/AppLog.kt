package com.guardianangel.mithrildefense.utils

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

    /** Truncate secrets for logs (tokens, tickets). */
    fun redact(value: String?, keepStart: Int = 6, keepEnd: Int = 4): String {
        if (value.isNullOrBlank()) return "<empty>"
        if (value.length <= keepStart + keepEnd) return "***"
        return value.take(keepStart) + "…" + value.takeLast(keepEnd) + " (len=${value.length})"
    }
}
