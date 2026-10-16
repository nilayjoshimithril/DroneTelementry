package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DronePoseDto(
    val position: DronePosePositionDto = DronePosePositionDto(),
    val rotation: DronePoseRotationDto = DronePoseRotationDto(),
    val confidence: Double = 0.0,
    @SerialName("captured_at") val capturedAt: Long? = null,
    @SerialName("frame_url") val frameUrl: String? = null,
)
