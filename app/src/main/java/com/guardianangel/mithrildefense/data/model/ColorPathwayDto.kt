package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mithril [SocketSubscribeColorPathwayModel] — `color-pathway-added` and
 * `on-session-detail.color_pathways[]`. Points are raw Unreal world units.
 */
@Serializable
data class ColorPathwayDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("spline_id") val splineId: String? = null,
    @SerialName("spline_color") val splineColor: Int? = null,
    @SerialName("spline_thickness") val splineThickness: Int? = null,
    // Legacy top-level fields (older payloads). Prefer [splineChunks] floor_ids/building_ids.
    @SerialName("spline_floors") val splineFloors: List<String>? = null,
    @SerialName("spline_buildings") val splineBuildings: List<String>? = null,
    @SerialName("spline_chunks") val splineChunks: List<ColorPathwayChunkDto>? = null,
    @SerialName("spline_points") val splinePoints: List<ColorPathwayPointDto>? = null,
)

@Serializable
data class ColorPathwayChunkDto(
    @SerialName("chunk_index") val chunkIndex: Int? = null,
    /** Inclusive end index into spline_points for this chunk (current API). */
    @SerialName("endpoint_index") val endpointIndex: Int? = null,
    /** Legacy alias for [endpointIndex]. */
    @SerialName("chunk_end_point_index") val chunkEndPointIndex: Int? = null,
    @SerialName("floor_ids") val floorIds: List<String>? = null,
    @SerialName("building_ids") val buildingIds: List<String>? = null,
)

@Serializable
data class ColorPathwayPointDto(
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
)

/** `color-pathway-removed` payload. */
@Serializable
data class ColorPathwayRemovedDto(
    @SerialName("spline_id") val splineId: String? = null,
)
