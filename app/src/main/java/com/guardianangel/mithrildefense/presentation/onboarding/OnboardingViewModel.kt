package com.guardianangel.mithrildefense.presentation.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.mithrildefense.data.model.CampusDto
import com.guardianangel.mithrildefense.data.model.DistrictResponseDto
import com.guardianangel.mithrildefense.data.remote.OAuthHelper
import com.guardianangel.mithrildefense.domain.interact.AuthInteract
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val inviteCode: String = "",
    val district: DistrictResponseDto? = null,
    val campuses: List<CampusDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface OnboardingNav {
    data object Home : OnboardingNav
}

class OnboardingViewModel(
    private val authInteract: AuthInteract,
    private val oauthHelper: OAuthHelper,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _nav = MutableSharedFlow<OnboardingNav>()
    val nav: SharedFlow<OnboardingNav> = _nav.asSharedFlow()

    fun onInviteCodeChanged(code: String) {
        _state.update { it.copy(inviteCode = code.trim(), error = null) }
    }

    fun submitInviteCode() {
        val code = _state.value.inviteCode
        if (code.isBlank()) {
            _state.update { it.copy(error = "Enter an invite code") }
            return
        }
        viewModelScope.launch {
            AppLog.i(TAG, "submitInviteCode code=$code")
            _state.update { it.copy(loading = true, error = null) }
            runCatching { authInteract.getDistrict(code) }
                .onSuccess { district ->
                    AppLog.i(TAG, "Invite OK district=${district.name}")
                    _state.update { it.copy(loading = false, district = district) }
                }
                .onFailure { e ->
                    AppLog.e(TAG, "Invite failed", e)
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Invite lookup failed")
                    }
                }
        }
    }

    fun continueLogin(activity: Activity) {
        val invite = _state.value.inviteCode
        val district = _state.value.district ?: return
        viewModelScope.launch {
            AppLog.i(TAG, "continueLogin district=${district.name}")
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val code = oauthHelper.authorize(activity, invite)
                AppLog.i(TAG, "OAuth code received — exchanging token")
                val token = authInteract.exchangeCode(code)
                val districtId = district.id.ifBlank {
                    token.userDetail.districts?.firstOrNull()?.id.orEmpty()
                }
                val campuses = if (districtId.isNotBlank()) {
                    authInteract.loadCampuses(districtId)
                } else {
                    token.userDetail.campuses?.map {
                        CampusDto(id = it.id, name = it.name.orEmpty(), districtId = it.districtId.orEmpty())
                    }.orEmpty()
                }
                if (campuses.isEmpty()) {
                    throw IllegalStateException("No campuses available for this account")
                }
                val campus = campuses.first()
                authInteract.selectCampus(
                    districtId = campus.districtId.ifBlank { districtId },
                    campus = campus,
                )
                campuses
            }.onSuccess { campuses ->
                AppLog.i(TAG, "Login success campuses=${campuses.size} → Home")
                _state.update { it.copy(loading = false, campuses = campuses) }
                _nav.emit(OnboardingNav.Home)
            }.onFailure { e ->
                AppLog.e(TAG, "Login failed", e)
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Login failed")
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val TAG = "OnboardingVM"
    }
}
