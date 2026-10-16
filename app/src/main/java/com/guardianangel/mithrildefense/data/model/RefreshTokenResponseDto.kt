package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RefreshTokenResponseDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ExpiresIn") val expiresIn: Int = 0,
    @SerialName("IdToken") val idToken: String = "",
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("TokenType") val tokenType: String = "",
)
