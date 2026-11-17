package com.guardianangel.dronetelementry.utils

import android.os.SystemClock

/**
 * Server time, derived from API responses' `Date` header. Elapsed-time displays (incident
 * "Running" timer) use this so a wrong device clock can't freeze or skew them.
 * Anchored to [SystemClock.elapsedRealtime], so device clock changes don't affect it either.
 */
object ServerClock {
    @Volatile
    private var anchor: Pair<Long, Long>? = null // serverMs to elapsedRealtime

    fun sync(serverMs: Long) {
        anchor = serverMs to SystemClock.elapsedRealtime()
    }

    /** Current server time; device time until the first API response arrives. */
    fun nowMs(): Long {
        val (serverMs, at) = anchor ?: return System.currentTimeMillis()
        return serverMs + (SystemClock.elapsedRealtime() - at)
    }
}
