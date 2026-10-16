package com.guardianangel.mithrildefense.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.local.SessionStore
import com.guardianangel.mithrildefense.data.model.OngoingIncidentDto
import com.guardianangel.mithrildefense.domain.interact.AuthInteract
import com.guardianangel.mithrildefense.domain.interact.HomeInteract
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeUiState(
    val campusName: String = "",
    val incidents: List<OngoingIncidentDto> = emptyList(),
    val loading: Boolean = false,
    val joiningId: String? = null,
    val error: String? = null,
)

sealed interface HomeNav {
    data class MapSession(
        val incidentId: String,
        val campusId: String,
        val wsTicket: String,
    ) : HomeNav

    data object Login : HomeNav
}

class HomeViewModel(
    private val homeInteract: HomeInteract,
    private val authInteract: AuthInteract,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _nav = MutableSharedFlow<HomeNav>()
    val nav: SharedFlow<HomeNav> = _nav.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            val campusName = sessionStore.getCampusName().orEmpty()
            AppLog.i(TAG, "Home init campus=$campusName")
            _state.update { it.copy(campusName = campusName) }
            refresh(showLoader = true)
        }
    }

    /** Mithril [startIncidentPolling] — silent refresh every [AppConfig.ONGOING_INCIDENTS_POLL_MS]. */
    fun startIncidentPolling() {
        if (pollingJob?.isActive == true) return
        AppLog.i(TAG, "startIncidentPolling intervalMs=${AppConfig.ONGOING_INCIDENTS_POLL_MS}")
        pollingJob = viewModelScope.launch {
            // Immediate silent refresh on start/resume, then keep polling.
            loadIncidents(showLoader = false)
            while (isActive) {
                delay(AppConfig.ONGOING_INCIDENTS_POLL_MS)
                loadIncidents(showLoader = false)
            }
        }
    }

    fun stopIncidentPolling() {
        AppLog.i(TAG, "stopIncidentPolling")
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refresh(showLoader: Boolean = true) {
        viewModelScope.launch {
            loadIncidents(showLoader = showLoader)
        }
    }

    private suspend fun loadIncidents(showLoader: Boolean) {
        if (showLoader) {
            AppLog.i(TAG, "Refreshing ongoing incidents")
            _state.update { it.copy(loading = true, error = null) }
        }
        runCatching { homeInteract.fetchOngoingIncidents() }
            .onSuccess { list ->
                val previousIds = _state.value.incidents.map { it.id }
                val nextIds = list.map { it.id }
                if (showLoader || previousIds != nextIds) {
                    AppLog.i(TAG, "Incidents loaded count=${list.size} ids=$nextIds")
                }
                _state.update { it.copy(loading = false, incidents = list, error = null) }
            }
            .onFailure { e ->
                AppLog.e(TAG, "Failed to load incidents", e)
                // Keep existing list on silent poll failures; only surface errors on manual refresh.
                if (showLoader) {
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Failed to load incidents")
                    }
                } else {
                    _state.update { it.copy(loading = false) }
                }
            }
    }

    fun join(incident: OngoingIncidentDto, userStatus: String? = null) {
        // Same guard as Mithril: ignore duplicate join while one is in flight.
        if (_state.value.joiningId != null) {
            AppLog.w(TAG, "join ignored — already joining ${_state.value.joiningId}")
            return
        }
        viewModelScope.launch {
            AppLog.i(TAG, "Join incident id=${incident.id} campusId=${incident.campusId}")
            _state.update { it.copy(joiningId = incident.id, error = null) }
            runCatching { homeInteract.joinIncident(incident.id, userStatus) }
                .onSuccess { joined ->
                    val ticket = joined.wsTicket?.ticket.orEmpty()
                    // Prefer join response campus, then incident's campus (district list may span campuses).
                    val campusId = joined.campusId
                        ?.takeIf { it.isNotBlank() }
                        ?: incident.campusId.takeIf { it.isNotBlank() }
                        ?: sessionStore.getCampusId().orEmpty()
                    if (campusId.isBlank()) {
                        AppLog.e(TAG, "Join OK but no campus_id for map")
                        _state.update {
                            it.copy(
                                joiningId = null,
                                error = "Joined but campus is missing for map",
                            )
                        }
                        return@onSuccess
                    }
                    // Persist campus used for this session (map/annotations APIs).
                    sessionStore.saveCampus(
                        districtId = joined.districtId
                            ?: incident.districtId.takeIf { it.isNotBlank() }
                            ?: sessionStore.getDistrictId().orEmpty(),
                        campusId = campusId,
                        campusName = joined.campusName
                            ?: sessionStore.getCampusName().orEmpty(),
                    )
                    AppLog.i(
                        TAG,
                        "Join OK → map session incident=${joined.id ?: incident.id} " +
                            "campus=$campusId status=${joined.status} ticket=${AppLog.redact(ticket)}",
                    )
                    _state.update { it.copy(joiningId = null) }
                    _nav.emit(
                        HomeNav.MapSession(
                            incidentId = joined.id ?: incident.id,
                            campusId = campusId,
                            wsTicket = ticket,
                        )
                    )
                }
                .onFailure { e ->
                    AppLog.e(TAG, "Join failed", e)
                    _state.update {
                        it.copy(joiningId = null, error = e.message ?: "Join failed")
                    }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            AppLog.i(TAG, "Logout from Home")
            stopIncidentPolling()
            authInteract.logout()
            _nav.emit(HomeNav.Login)
        }
    }

    override fun onCleared() {
        stopIncidentPolling()
        super.onCleared()
    }

    companion object {
        private const val TAG = "HomeVM"
    }
}
