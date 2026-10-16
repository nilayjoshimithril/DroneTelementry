package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccessTokenResponseDto(
    @SerialName("auth_detail") val authDetail: AuthDetailDto,
    @SerialName("user_detail") val userDetail: UserDto,
)
