package com.guardianangel.mithrildefense.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.mithrildefense.domain.interact.AuthInteract
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface SplashNav {
    data object Login : SplashNav
    data object Home : SplashNav
}

class SplashViewModel(
    private val authInteract: AuthInteract,
) : ViewModel() {

    private val _nav = MutableSharedFlow<SplashNav>(replay = 1)
    val nav: SharedFlow<SplashNav> = _nav.asSharedFlow()

    init {
        viewModelScope.launch {
            AppLog.i(TAG, "Checking stored session…")
            val session = authInteract.restoreSession()
            // Keep user logged in whenever a token is stored; logout only on 401.
            val dest = if (session != null) SplashNav.Home else SplashNav.Login
            AppLog.i(
                TAG,
                "Session restore → $dest user=${session?.userDetail?.email} " +
                    "districtOk=${authInteract.hasValidSession()}",
            )
            _nav.emit(dest)
        }
    }

    companion object {
        private const val TAG = "SplashVM"
    }
}
