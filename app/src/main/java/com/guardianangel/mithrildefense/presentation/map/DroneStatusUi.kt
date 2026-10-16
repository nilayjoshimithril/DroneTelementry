package com.guardianangel.mithrildefense.presentation.map

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.guardianangel.mithrildefense.R
import com.guardianangel.mithrildefense.domain.model.DroneMapPose
import kotlin.math.abs

enum class StatusTone {
    OK,
    WARN,
    BAD,
    NEUTRAL,
}

fun statusTone(raw: String?): StatusTone {
    val value = raw?.trim()?.uppercase().orEmpty()
    if (value.isEmpty() || value == "—" || value == "-") return StatusTone.NEUTRAL
    return when {
        value.contains("FAIL") ||
            value.contains("ERROR") ||
            value.contains("CRIT") ||
            value.contains("OFF") ||
            value.contains("DEAD") ||
            value.contains("FAULT") -> StatusTone.BAD
        value.contains("WARN") ||
            value.contains("DEGRAD") ||
            value.contains("LOW") ||
            value.contains("WEAK") -> StatusTone.WARN
        value.contains("OK") ||
            value.contains("GOOD") ||
            value.contains("ACTIVE") ||
            value.contains("ONLINE") ||
            value.contains("NORMAL") ||
            value.contains("READY") ||
            value.contains("PASS") -> StatusTone.OK
        else -> StatusTone.NEUTRAL
    }
}

fun displayStatus(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return value.ifBlank { "—" }
}

@DrawableRes
fun StatusTone.pillBackground(): Int = when (this) {
    StatusTone.OK -> R.drawable.bg_status_pill_ok
    StatusTone.WARN -> R.drawable.bg_status_pill_warn
    StatusTone.BAD -> R.drawable.bg_status_pill_bad
    StatusTone.NEUTRAL -> R.drawable.bg_status_pill_neutral
}

@ColorRes
fun StatusTone.textColor(): Int = when (this) {
    StatusTone.OK -> R.color.md_green
    StatusTone.WARN -> R.color.md_orange
    StatusTone.BAD -> R.color.md_red
    StatusTone.NEUTRAL -> R.color.md_hint
}

fun TextView.bindStatusPill(label: String, raw: String?) {
    val value = displayStatus(raw)
    val tone = statusTone(raw)
    text = "$label  ·  $value"
    setBackgroundResource(tone.pillBackground())
    setTextColor(ContextCompat.getColor(context, tone.textColor()))
}

fun batteryTone(percent: Int): StatusTone = when {
    percent <= 15 -> StatusTone.BAD
    percent <= 30 -> StatusTone.WARN
    else -> StatusTone.OK
}

fun formatHeading(degrees: Float): String {
    val normalized = ((degrees % 360f) + 360f) % 360f
    return "${normalized.toInt()}°"
}

fun formatAltitude(positionZ: Double): String = String.format("%.1f m", abs(positionZ))

fun formatConfidence(confidence: Double): String {
    val pct = if (confidence <= 1.0) (confidence * 100.0) else confidence
    return "${pct.toInt().coerceIn(0, 100)}%"
}

fun formatPoseAge(capturedAt: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (capturedAt == null || capturedAt <= 0L) return "—"
    // captured_at may be ms epoch or a relative/monotonic stamp; treat large values as epoch ms.
    val ageMs = if (capturedAt > 1_000_000_000_000L) {
        (nowMs - capturedAt).coerceAtLeast(0L)
    } else {
        // Likely non-wall-clock; show raw freshness unknown rather than nonsense age.
        return "—"
    }
    return when {
        ageMs < 1_000L -> String.format("%.1fs", ageMs / 1000.0)
        ageMs < 60_000L -> "${ageMs / 1000}s"
        else -> "${ageMs / 60_000}m"
    }
}

fun DroneMapPose.listMetaLine(): String {
    val stateLabel = state?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
    val motor = motorStatus?.trim()?.takeIf { it.isNotEmpty() }
    return if (motor != null) "$stateLabel · Motor $motor" else stateLabel
}

fun TextView.applyToneColor(tone: StatusTone) {
    setTextColor(ContextCompat.getColor(context, tone.textColor()))
}

fun android.widget.ProgressBar.applyBatteryTint(percent: Int) {
    val color = ContextCompat.getColor(context, batteryTone(percent).textColor())
    progressTintList = ColorStateList.valueOf(color)
}
