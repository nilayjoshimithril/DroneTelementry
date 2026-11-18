package com.guardianangel.dronetelementry.kiosk

import android.app.Activity
import android.os.Bundle
import com.guardianangel.dronetelementry.utils.AppLog

/**
 * QR / zero-touch setup, step 2 (required on Android 12+): we're now device owner — apply the
 * kiosk policies before setup finishes, so the first thing shown afterwards is this app, locked.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "Admin policy compliance → applying kiosk policies")
        KioskManager.applyPolicies(this)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "KioskProvisioning"
    }
}
