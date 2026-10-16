package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MosaicAnnotationDto(
    val id: String = "",
    val type: String = "",
    val category: String? = null,
    @SerialName("display_id") val displayId: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val name: String? = null,
    @SerialName("Lat") val lat: Double? = null,
    @SerialName("Lng") val lng: Double? = null,
    @SerialName("floor_id") val floorId: String? = null,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("campus_id") val campusId: String? = null,
) {
    val effectiveFloorId: String
        get() = floorId?.trim().orEmpty()

    val displayLabel: String
        get() = displayId?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: type.ifBlank { "Marker" }
}
