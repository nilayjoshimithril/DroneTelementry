package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MapConfigDataDto(
    @SerialName("resolution_x") val resolutionX: Int = 1920,
    @SerialName("resolution_y") val resolutionY: Int = 1080,
    @SerialName("origin_x") val originX: Double = 0.0,
    @SerialName("origin_y") val originY: Double = 0.0,
)
