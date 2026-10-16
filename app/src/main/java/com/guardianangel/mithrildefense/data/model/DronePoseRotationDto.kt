package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DronePoseRotationDto(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val w: Double = 1.0,
)
