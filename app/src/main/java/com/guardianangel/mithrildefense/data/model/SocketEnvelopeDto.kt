package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SocketEnvelopeDto(
    @SerialName("channel") val channel: String? = null,
    @SerialName("payload") val payload: JsonElement? = null,
)
