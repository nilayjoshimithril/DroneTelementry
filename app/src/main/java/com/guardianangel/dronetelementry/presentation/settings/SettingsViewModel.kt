package com.guardianangel.dronetelementry.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.dronetelementry.data.model.CampusDto
import com.guardianangel.dronetelementry.domain.interact.AuthInteract
import com.guardianangel.dronetelementry.domain.interact.MapInteract
import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val userName: String = "",
    val userEmail: String = "",
    val zoomLevel: Float = MapInteract.DEFAULT_MAP_ZOOM,
    val campuses: List<CampusDto> = emptyList(),
    val campusesLoading: Boolean = false,
    val campusesLoaded: Boolean = false,
    val campusesError: String? = null,
)

sealed interface SettingsNav {
    data object Login : SettingsNav
}

/** Settings home + the "pick a campus to preview zoom" list. */
class SettingsViewModel(
    private val authInteract: AuthInteract,
    private val mapInteract: MapInteract,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _nav = MutableSharedFlow<SettingsNav>()
    val nav: SharedFlow<SettingsNav> = _nav.asSharedFlow()

    private var campusJob: Job? = null

    init {
        viewModelScope.launch {
            val user = runCatching { authInteract.currentUser() }.getOrNull()
            val name = listOfNotNull(
                user?.firstName?.takeIf { it.isNotBlank() },
                user?.lastName?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            _state.update {
                it.copy(userName = name.ifBlank { user?.email.orEmpty() }, userEmail = user?.email.orEmpty())
            }
        }
        refreshZoom()
    }

    /** Re-read the saved zoom (called on resume, after the preview screen may have saved one). */
    fun refreshZoom() {
        viewModelScope.launch {
            val zoom = runCatching { mapInteract.mapZoomLevel() }.getOrDefault(MapInteract.DEFAULT_MAP_ZOOM)
            _state.update { it.copy(zoomLevel = zoom) }
        }
    }

    /** Every campus in every district — internal users can preview any of them. */
    fun loadCampuses(force: Boolean = false) {
        if (campusJob?.isActive == true) return
        if (!force && _state.value.campusesLoaded) return
        campusJob = viewModelScope.launch {
            _state.update { it.copy(campusesLoading = true, campusesError = null) }
            runCatching { authInteract.loadAllCampuses() }.onSuccess { campuses ->
                _state.update {
                    it.copy(campuses = campuses, campusesLoading = false, campusesLoaded = true)
                }
            }.onFailure { e ->
                AppLog.e(TAG, "Campus list failed", e)
                _state.update {
                    it.copy(
                        campusesLoading = false,
                        campusesLoaded = false,
                        campusesError = e.message ?: "Failed to load campuses",
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            AppLog.i(TAG, "Logout from Settings")
            authInteract.logout()
            _nav.emit(SettingsNav.Login)
        }
    }

    companion object {
        private const val TAG = "SettingsVM"
    }
}
