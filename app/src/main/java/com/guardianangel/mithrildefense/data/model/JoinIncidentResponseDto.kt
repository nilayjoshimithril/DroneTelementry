package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JoinIncidentResponseDto(
    @SerialName("ID") val id: String? = null,
    @SerialName("campus_id") val campusId: String? = null,
    @SerialName("campus_name") val campusName: String? = null,
    @SerialName("district_id") val districtId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("is_drill") val isDrill: Boolean? = null,
    @SerialName("ws_ticket") val wsTicket: WsTicketDto? = null,
)
