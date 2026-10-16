package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsTicketResponseDto(
    @SerialName("ticket") val ticket: String,
)
