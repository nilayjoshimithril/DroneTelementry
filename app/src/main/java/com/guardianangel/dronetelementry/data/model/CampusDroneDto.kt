package com.guardianangel.dronetelementry.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET campuses/drones/` page: `data.data[]` + `data.meta` (limit / page / total). */
@Serializable
data class CampusDronePageDto(
    val data: List<CampusDroneDto> = emptyList(),
    val meta: AnnotationMetaDto = AnnotationMetaDto(),
)

/**
 * A drone registered to a campus. Static roster only — the live position comes from the
 * incident socket's `on-drone-telemetry` channel, matched on [displayId].
 */
@Serializable
data class CampusDroneDto(
    @SerialName("ID") val id: String = "",
    @SerialName("campus_id") val campusId: String? = null,
    @SerialName("building_id") val buildingId: String? = null,
    @SerialName("nest_id") val nestId: String? = null,
    /** Matches telemetry `display_id` (e.g. "201.001"). */
    @SerialName("display_id") val displayId: String = "",
    @SerialName("drone_number") val droneNumber: Int? = null,
    /** User assigned to fly this drone — drives the map's default drone focus. */
    @SerialName("pilot_id") val pilotId: String? = null,
    /** DAZZLER, KINETIC_STRIKE, PEPPER_HOT, SONIC… (absent on some drones). */
    @SerialName("type") val type: String? = null,
    @SerialName("flight_hours") val flightHours: Double? = null,
    /** AVAILABLE, … */
    @SerialName("status") val status: String? = null,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    @SerialName("weapon") val weapons: List<DroneWeaponDto>? = null,
    @SerialName("video_stream_url") val videoStreamUrl: String? = null,
)

@Serializable
data class DroneWeaponDto(
    @SerialName("ID") val id: String = "",
    @SerialName("drone_id") val droneId: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("rounds_remaining") val roundsRemaining: Int? = null,
    @SerialName("total_rounds") val totalRounds: Int? = null,
)
