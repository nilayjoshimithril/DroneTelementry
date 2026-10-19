package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Map-relevant slice of Mithril `on-session-detail` — full snapshot of threats + pathways. */
@Serializable
data class SessionDetailDto(
    @SerialName("incident_id") val incidentId: String? = null,
    @SerialName("incident_status") val incidentStatus: String? = null,
    @SerialName("color_pathways") val colorPathways: List<ColorPathwayDto>? = null,
    val threats: List<ThreatDto>? = null,
)
