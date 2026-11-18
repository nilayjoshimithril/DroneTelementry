package com.guardianangel.dronetelementry.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import com.guardianangel.dronetelementry.utils.AppLog

/**
 * QR / zero-touch setup, step 1 (required on Android 12+): the setup wizard asks which mode
 * we want. Always a fully managed device (device owner) — that's what kiosk needs.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "Provisioning mode requested → fully managed device")
        setResult(
            RESULT_OK,
            Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            ),
        )
        finish()
    }

    companion object {
        private const val TAG = "KioskProvisioning"
    }
}
