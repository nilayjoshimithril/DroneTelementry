package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserCampusDto(
    @SerialName("ID") val id: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("district_id") val districtId: String? = null,
)
