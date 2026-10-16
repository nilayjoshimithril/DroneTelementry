package com.guardianangel.mithrildefense.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView
import com.guardianangel.mithrildefense.R

/**
 * Matches Mithril [DefaultScreenUI] / [App] background:
 * - API ≤ 30 → pre-blurred [R.drawable.app_background_api_29]
 * - API 31+ → vector [R.drawable.app_background] + Compose-equivalent `.blur(330.dp)`
 */
fun ImageView.applyMithrilBlurredBackground() {
    scaleType = ImageView.ScaleType.CENTER_CROP
    adjustViewBounds = false
    // Mithril: API ≤ 30 uses pre-blurred PNG (Compose blur unsupported / costly).
    if (Build.VERSION.SDK_INT <= 30) {
        setImageResource(R.drawable.app_background_api_29)
        return
    }

    setImageResource(R.drawable.app_background)
    // Compose Modifier.blur(330.dp) → radius in px = 330 * density
    val radiusPx = 330f * resources.displayMetrics.density
    setRenderEffect(
        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP),
    )
}
