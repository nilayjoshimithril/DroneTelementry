package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OverviewDto(
    val url: String = "",
    @SerialName("unreal_to_pixels") val unrealToPixels: Double = 1.0,
    @SerialName("resolution_x") val resolutionX: Int = 0,
    @SerialName("resolution_y") val resolutionY: Int = 0,
)
