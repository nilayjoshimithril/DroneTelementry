package com.guardianangel.mithrildefense.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Mithril-style 12-hour clock from ISO CreatedAt (e.g. 2026-10-13T10:22:40.618153Z). */
fun String?.to12HourFormat(): String {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    return runCatching {
        val instant = Instant.parse(raw)
        val zoned = instant.atZone(ZoneId.systemDefault())
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(zoned)
    }.getOrElse {
        AppLog.w("TimeFormat", "TIME_ERROR_$raw", it)
        raw
    }
}
