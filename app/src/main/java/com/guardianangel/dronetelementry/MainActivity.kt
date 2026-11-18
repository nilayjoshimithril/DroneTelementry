package com.guardianangel.dronetelementry

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.guardianangel.dronetelementry.data.remote.SessionEvents
import com.guardianangel.dronetelementry.databinding.ActivityMainBinding
import com.guardianangel.dronetelementry.kiosk.KioskManager
import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sessionEvents: SessionEvents by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installKioskBackGuard()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionEvents.unauthorized.filter { it }.collect {
                    sessionEvents.consumeUnauthorized()
                    val navController =
                        (supportFragmentManager.findFragmentById(R.id.nav_host) as? NavHostFragment)
                            ?.navController
                            ?: return@collect
                    val currentId = navController.currentDestination?.id
                    if (currentId == R.id.loginFragment || currentId == R.id.splashFragment) {
                        AppLog.d(TAG, "401 ignored — already on auth screen")
                        return@collect
                    }
                    AppLog.w(TAG, "401 received — navigating to login")
                    runCatching {
                        navController.navigate(R.id.action_global_to_login)
                    }.onFailure { e ->
                        AppLog.e(TAG, "Failed to navigate to login after 401", e)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Kiosk: pin the app (no-op unless the app is device owner).
        KioskManager.enterLockTask(this)
    }

    /** Kiosk: back on the first screen must not close the app (there's nothing behind it). */
    private fun installKioskBackGuard() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val navController =
                    (supportFragmentManager.findFragmentById(R.id.nav_host) as? NavHostFragment)
                        ?.navController
                if (navController?.popBackStack() == true) return
                if (KioskManager.isKiosk(this@MainActivity)) {
                    AppLog.d(TAG, "Back at root ignored — kiosk mode")
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
