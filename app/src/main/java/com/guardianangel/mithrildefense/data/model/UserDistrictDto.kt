package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDistrictDto(
    @SerialName("ID") val id: String = "",
    @SerialName("name") val name: String? = null,
)
