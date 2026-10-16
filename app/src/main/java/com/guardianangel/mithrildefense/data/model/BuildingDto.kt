package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuildingDto(
    val id: String = "",
    val name: String = "",
    @SerialName("building_id") val buildingId: String? = null,
    val floors: List<FloorDto> = emptyList(),
)
