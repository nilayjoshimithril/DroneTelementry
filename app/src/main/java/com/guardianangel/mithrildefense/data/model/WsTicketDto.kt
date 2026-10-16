package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsTicketDto(
    @SerialName("ticket") val ticket: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)
