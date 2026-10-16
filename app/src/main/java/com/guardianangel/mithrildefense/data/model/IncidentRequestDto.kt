package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IncidentRequestDto(
    @SerialName("incident_id") val incidentId: String,
    @SerialName("incident_reason") val incidentReason: String? = null,
    @SerialName("platform") val platform: String = "mobile",
)
