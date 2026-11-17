package com.guardianangel.dronetelementry.presentation.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.dronetelementry.data.remote.OAuthHelper
import com.guardianangel.dronetelementry.data.remote.SessionEvents
import com.guardianangel.dronetelementry.domain.interact.AuthInteract
import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface OnboardingNav {
    data object Home : OnboardingNav
    /** User backed out of the Microsoft page — close the app. */
    data object Exit : OnboardingNav
    data class Failed(val message: String) : OnboardingNav
}

/**
 * Login is only the Microsoft AD page: no invite code, no district / campus selection
 * (internal users can open every campus in every district).
 */
class OnboardingViewModel(
    private val authInteract: AuthInteract,
    private val oauthHelper: OAuthHelper,
    private val sessionEvents: SessionEvents,
) : ViewModel() {

    // Buffered: results arrive while the Microsoft page still covers this screen (STOPPED),
    // so events must wait for the collector instead of being dropped.
    private val _nav = Channel<OnboardingNav>(Channel.BUFFERED)
    val nav: Flow<OnboardingNav> = _nav.receiveAsFlow()

    private var started = false
    private var loginJob: Job? = null

    /** Opens the Microsoft page once per screen (survives rotation). */
    fun startLoginIfNeeded(activity: Activity) {
        if (started) return
        started = true
        signIn(activity)
    }

    fun signIn(activity: Activity) {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            AppLog.i(TAG, "signIn → Microsoft AD")
            runCatching {
                val code = oauthHelper.authorize(activity)
                AppLog.i(TAG, "OAuth code received — exchanging token")
                authInteract.exchangeCode(code)
            }.onSuccess {
                AppLog.i(TAG, "Login success → Home")
                sessionEvents.armAutoJoin()
                _nav.send(OnboardingNav.Home)
            }.onFailure { e ->
                if (e.message == USER_CANCELLED) {
                    AppLog.w(TAG, "User closed the Microsoft page — exiting")
                    _nav.send(OnboardingNav.Exit)
                } else {
                    AppLog.e(TAG, "Login failed", e)
                    _nav.send(OnboardingNav.Failed(e.message ?: "Login failed"))
                }
            }
        }
    }

    companion object {
        private const val TAG = "OnboardingVM"
        /** Error OAuthWebViewActivity reports when the user presses back. */
        private const val USER_CANCELLED = "user_cancelled"
    }
}
