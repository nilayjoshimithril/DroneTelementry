package com.guardianangel.dronetelementry.utils

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardianangel.dronetelementry.R

/**
 * App-wide device behaviour, installed once from the Application:
 *
 * - **Screen always on** while any of our screens is visible, whatever the system screen
 *   timeout is (FLAG_KEEP_SCREEN_ON on every activity window).
 * - **Low battery dialog** when the tablet battery drops to [LOW_BATTERY_PERCENT] or below while
 *   not charging. Shown once per discharge; re-armed when the tablet is plugged in or climbs back
 *   above the threshold. Closes itself if a charger is connected.
 */
class DeviceStatusWatcher private constructor(
    private val app: Application,
) : Application.ActivityLifecycleCallbacks {

    private var resumed: Activity? = null
    private var dialog: AlertDialog? = null

    /** Low and not yet acknowledged by the user: show (again) on the screen in front. */
    private var alertPending = false
    /** User dismissed the alert for this discharge; don't nag again until re-armed. */
    private var acknowledged = false
    private var lastPercent = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }

    private fun start() {
        app.registerActivityLifecycleCallbacks(this)
        // ACTION_BATTERY_CHANGED is sticky: registering also returns the current level.
        app.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let(::onBattery)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        lastPercent = percent

        if (charging || percent > LOW_BATTERY_PERCENT) {
            // Re-arm for the next discharge, and close the alert if a charger was connected.
            alertPending = false
            acknowledged = false
            dismissDialog()
            return
        }
        if (!acknowledged) {
            alertPending = true
            if (dialog?.isShowing == true) updateDialogMessage() else showIfPossible()
        }
    }

    private fun showIfPossible() {
        val activity = resumed ?: return
        if (!alertPending || activity.isFinishing || activity.isDestroyed) return
        if (dialog?.isShowing == true) return
        dialog = runCatching {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.battery_low_title)
                .setMessage(message())
                .setIcon(R.drawable.ic_battery_alert)
                .setCancelable(true)
                .setPositiveButton(R.string.dismiss) { _, _ -> acknowledge() }
                .setOnCancelListener { acknowledge() }
                .show()
        }.onFailure { AppLog.e(TAG, "Could not show low battery dialog", it) }.getOrNull()
    }

    private fun message(): String =
        app.getString(R.string.battery_low_message, lastPercent.coerceAtLeast(0))

    private fun updateDialogMessage() {
        dialog?.setMessage(message())
    }

    private fun acknowledge() {
        acknowledged = true
        alertPending = false
        dialog = null
    }

    private fun dismissDialog() {
        dialog?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
        dialog = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
        showIfPossible()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
        // Screen is going away: drop its dialog without acknowledging, so the next screen shows it.
        if (dialog?.isShowing == true) {
            dialog?.setOnCancelListener(null)
            dismissDialog()
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val TAG = "DeviceStatus"
        const val LOW_BATTERY_PERCENT = 15

        fun install(app: Application) = DeviceStatusWatcher(app).start()
    }
}
