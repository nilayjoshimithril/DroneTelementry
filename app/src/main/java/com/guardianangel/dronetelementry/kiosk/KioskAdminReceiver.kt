package com.guardianangel.dronetelementry.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.guardianangel.dronetelementry.utils.AppLog

/**
 * Device-admin entry point. Provisioning this receiver as **device owner** turns the tablet into
 * a single-app kiosk (see [KioskManager]):
 *
 *   adb shell dpm set-device-owner com.guardianangel.dronetelementry/.kiosk.KioskAdminReceiver
 */
class KioskAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        AppLog.i(TAG, "Device admin enabled")
        KioskManager.applyPolicies(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        AppLog.w(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "KioskAdmin"
    }
}
