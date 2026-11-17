package com.guardianangel.dronetelementry.presentation.settings

import android.content.Context
import com.guardianangel.dronetelementry.R
import com.guardianangel.dronetelementry.domain.interact.MapInteract
import kotlin.math.abs

/** "1.50×", or "1.00× (Default)" for the fit-campus zoom. */
internal fun Context.formatZoomLevel(level: Float): String {
    val value = getString(R.string.zoom_value, level)
    return if (abs(level - MapInteract.DEFAULT_MAP_ZOOM) < 0.001f) {
        getString(R.string.zoom_value_default, value)
    } else {
        value
    }
}
