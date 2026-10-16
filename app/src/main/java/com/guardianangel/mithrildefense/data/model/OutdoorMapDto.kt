package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OutdoorMapDto(
    val cols: Int = 0,
    val rows: Int = 0,
    @SerialName("unreal_to_pixels") val unrealToPixels: Double = 1.0,
    val config: MapConfigDataDto = MapConfigDataDto(),
    val overview: OverviewDto? = null,
    @Serializable(with = MapTileListSerializer::class)
    val tiles: List<MapTileDto> = emptyList(),
)
