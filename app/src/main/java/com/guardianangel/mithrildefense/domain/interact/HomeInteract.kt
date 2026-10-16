package com.guardianangel.mithrildefense.domain.interact

import com.guardianangel.mithrildefense.data.local.SessionStore
import com.guardianangel.mithrildefense.data.model.IncidentRequestDto
import com.guardianangel.mithrildefense.data.model.JoinIncidentResponseDto
import com.guardianangel.mithrildefense.data.model.OngoingIncidentDto
import com.guardianangel.mithrildefense.data.remote.ApiService
import com.guardianangel.mithrildefense.utils.AppLog

class HomeInteract(
    private val api: ApiService,
    private val sessionStore: SessionStore,
) {
    /**
     * Home screen ongoing list — **district_id only** (no campus_id).
     */
    suspend fun fetchOngoingIncidents(): List<OngoingIncidentDto> {
        val districtId = sessionStore.getDistrictId()
            ?: throw IllegalStateException("No district selected")
        AppLog.i(TAG, "API GET incidents/ongoing?district_id=$districtId")
        val response = api.getOngoingIncidents(districtId)
        val list = response.data.orEmpty()
        AppLog.d(
            TAG,
            "ongoing error=${response.error} message=${response.message} count=${list.size} " +
                "ids=${list.map { it.id }} campuses=${list.map { it.campusId }}",
        )
        if (response.error == true) {
            throw IllegalStateException(response.message ?: "Failed to load incidents")
        }
        return list
    }

    /**
     * Join mirrors Mithril: POST incidents/join with
     * `{ incident_id, incident_reason (optional user status), platform=mobile }`.
     */
    suspend fun joinIncident(
        incidentId: String,
        userStatus: String? = null,
    ): JoinIncidentResponseDto {
        AppLog.i(
            TAG,
            "API POST incidents/join incident_id=$incidentId userStatus=$userStatus platform=mobile",
        )
        val response = api.joinIncident(
            IncidentRequestDto(
                incidentId = incidentId,
                incidentReason = userStatus,
                platform = "mobile",
            )
        )
        AppLog.d(
            TAG,
            "join error=${response.error} message=${response.message} " +
                "incidentId=${response.data?.id} campusId=${response.data?.campusId} " +
                "status=${response.data?.status} " +
                "wsTicket=${AppLog.redact(response.data?.wsTicket?.ticket)}",
        )
        if (response.error == true || response.data == null) {
            throw IllegalStateException(response.message ?: "Failed to join incident")
        }
        val ticket = response.data.wsTicket?.ticket
        if (ticket.isNullOrBlank()) {
            throw IllegalStateException("Join response missing ws_ticket")
        }
        return response.data
    }

    companion object {
        private const val TAG = "HomeApi"
    }
}
