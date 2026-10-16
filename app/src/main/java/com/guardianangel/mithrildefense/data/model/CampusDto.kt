package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CampusDto(
    @SerialName("ID") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("district_id") val districtId: String = "",
    @SerialName("latitude") val latitude: String? = null,
    @SerialName("longitude") val longitude: String? = null,
)
