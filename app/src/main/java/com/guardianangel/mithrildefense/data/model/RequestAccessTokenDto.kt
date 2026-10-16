package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RequestAccessTokenDto(
    @SerialName("code") val code: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("type") val type: String = "Internal",
)
