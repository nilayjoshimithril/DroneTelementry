package com.guardianangel.dronetelementry.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import com.guardianangel.dronetelementry.MainActivity
import com.guardianangel.dronetelementry.utils.AppLog

/**
 * Single-app kiosk ("exam mode").
 *
 * - The app is the device's HOME screen, so it is what shows after unlock and after reboot.
 * - When provisioned as device owner it pins itself with lock task mode: no home, recents,
 *   notification shade or status bar, so no other app can be opened. The lock screen and the
 *   power menu stay available.
 *
 * Two ways to get there:
 * - Android Management API (production): Google's Android Device Policy is device owner,
 *   installs this app from managed Google Play and pins it (installType KIOSK). This class then
 *   only sees lock task as active — see [isKiosk]. Setup: tools/amapi/README.md.
 * - adb `dpm set-device-owner` (test tablets): this app is device owner and pins itself.
 *
 * Without either, nothing is enforced (the app just behaves as a selectable launcher).
 */
object KioskManager {

    private const val TAG = "Kiosk"

    /** Restrictions that would let a user escape the kiosk. */
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
    )

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) = ComponentName(context, KioskAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    fun isLockTaskActive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Kiosk is enforced: back must never leave the app. Either we are device owner (adb-set-up
     * tablets) or an MDM — Android Management API / Intune "single app kiosk" — has pinned us.
     */
    fun isKiosk(context: Context): Boolean = isDeviceOwner(context) || isLockTaskActive(context)

    /** Device-owner policies. Idempotent — safe to call on every launch. */
    fun applyPolicies(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
                )
            }
            // Always our app as HOME, without the "choose a launcher" prompt.
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin,
                home,
                ComponentName(context, MainActivity::class.java),
            )
            RESTRICTIONS.forEach { dpm.addUserRestriction(admin, it) }
            AppLog.i(TAG, "Kiosk policies applied")
        }.onFailure { AppLog.e(TAG, "Failed to apply kiosk policies", it) }
    }

    /** Pins the app (lock task). No-op unless device owner. */
    fun enterLockTask(activity: Activity) {
        if (!isDeviceOwner(activity) || isLockTaskActive(activity)) return
        applyPolicies(activity)
        runCatching { activity.startLockTask() }
            .onSuccess { AppLog.i(TAG, "Lock task started") }
            .onFailure { AppLog.e(TAG, "startLockTask failed", it) }
    }

    /**
     * Fully removes kiosk mode: unpins, drops policies and gives up device owner (after this the
     * app can be uninstalled normally). Exposed only in debug builds.
     */
    fun disableKiosk(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        runCatching { if (isLockTaskActive(activity)) activity.stopLockTask() }
        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
            RESTRICTIONS.forEach { dpm.clearUserRestriction(admin, it) }
            dpm.setLockTaskPackages(admin, emptyArray())
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(activity.packageName)
            AppLog.w(TAG, "Kiosk disabled — device owner cleared")
        }.onFailure { AppLog.e(TAG, "Failed to disable kiosk", it) }
    }
}
