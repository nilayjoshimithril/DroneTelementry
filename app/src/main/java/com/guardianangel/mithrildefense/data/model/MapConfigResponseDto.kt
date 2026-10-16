package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MapConfigResponseDto(
    val id: String? = null,
    @SerialName("district_id") val districtId: String? = null,
    @SerialName("campus_id") val campusId: String = "",
    @SerialName("campus_name") val campusName: String = "",
    @SerialName("campus_latitude") val campusLatitude: String? = null,
    @SerialName("campus_longitude") val campusLongitude: String? = null,
    @SerialName("campus_geo_latitude") val campusGeoLatitude: String? = null,
    @SerialName("campus_geo_longitude") val campusGeoLongitude: String? = null,
    @SerialName("campus_unreal_location_x") val campusUnrealLocationX: String? = null,
    @SerialName("campus_unreal_location_y") val campusUnrealLocationY: String? = null,
    @SerialName("campus_yaw_degree") val campusYawDegree: String? = null,
    val config: MapConfigDataDto? = null,
    val outdoor: OutdoorMapDto? = null,
    val buildings: List<BuildingDto> = emptyList(),
)
