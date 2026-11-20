package com.guardianangel.dronetelementry.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.dronetelementry.config.AppConfig
import com.guardianangel.dronetelementry.data.model.OngoingIncidentDto
import com.guardianangel.dronetelementry.data.remote.SessionEvents
import com.guardianangel.dronetelementry.domain.interact.HomeInteract
import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeUiState(
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
        /** Incident CreatedAt (ISO) — drives the map header's running timer. */
        val startedAt: String?,
    ) : HomeNav
}

class HomeViewModel(
    private val homeInteract: HomeInteract,
    private val sessionEvents: SessionEvents,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Buffered: the start-up auto-join can finish before Home starts collecting.
    private val _nav = Channel<HomeNav>(Channel.BUFFERED)
    val nav: Flow<HomeNav> = _nav.receiveAsFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            AppLog.i(TAG, "Home init")
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
                autoJoinSingleIncident(list)
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

    /**
     * Auto-join whenever exactly one incident is ongoing and the user hasn't joined it before
     * (an incident they joined and left is never auto-joined again). See [SessionEvents.takeAutoJoin].
     */
    private fun autoJoinSingleIncident(list: List<OngoingIncidentDto>) {
        val id = sessionEvents.takeAutoJoin(list.map { it.id }) ?: return
        val incident = list.first { it.id == id }
        AppLog.i(TAG, "Auto-join single ongoing incident id=${incident.id}")
        join(incident)
    }

    fun join(incident: OngoingIncidentDto, userStatus: String? = null) {
        // Same guard as Mithril: ignore duplicate join while one is in flight.
        if (_state.value.joiningId != null) {
            AppLog.w(TAG, "join ignored — already joining ${_state.value.joiningId}")
            return
        }
        viewModelScope.launch {
            AppLog.i(TAG, "Join incident id=${incident.id} campusId=${incident.campusId}")
            sessionEvents.markIncidentJoined(incident.id)
            _state.update { it.copy(joiningId = incident.id, error = null) }
            runCatching { homeInteract.joinIncident(incident.id, userStatus) }
                .onSuccess { joined ->
                    val ticket = joined.wsTicket?.ticket.orEmpty()
                    // Prefer join response campus, then incident's campus (district list may span campuses).
                    val campusId = joined.campusId
                        ?.takeIf { it.isNotBlank() }
                        ?: incident.campusId
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
                    AppLog.i(
                        TAG,
                        "Join OK → map session incident=${joined.id ?: incident.id} " +
                            "campus=$campusId status=${joined.status} ticket=${AppLog.redact(ticket)}",
                    )
                    _state.update { it.copy(joiningId = null) }
                    _nav.send(
                        HomeNav.MapSession(
                            incidentId = joined.id ?: incident.id,
                            campusId = campusId,
                            wsTicket = ticket,
                            startedAt = incident.createdAt,
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

    override fun onCleared() {
        stopIncidentPolling()
        super.onCleared()
    }

    companion object {
        private const val TAG = "HomeVM"
    }
}
