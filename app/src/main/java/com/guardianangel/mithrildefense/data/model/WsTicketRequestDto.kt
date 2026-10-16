package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsTicketRequestDto(
    @SerialName("incident_id") val incidentId: String,
)
