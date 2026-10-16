package com.guardianangel.mithrildefense.domain.model

import kotlin.math.atan2

data class DroneTelemetry(
    val displayId: String,
    val deviceId: String,
    val droneIp: String?,
    val state: String?,
    val batteryPercent: Int,
    val deviceType: String?,
    /** Optional map placement from telemetry; otherwise inferred from footprint + altitude. */
    val buildingId: String? = null,
    val floorId: String? = null,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val rotationX: Double,
    val rotationY: Double,
    val rotationZ: Double,
    val rotationW: Double,
    val confidence: Double,
    val capturedAt: Long?,
    val frameUrl: String?,
    val videoDeviceStatus: String?,
    val micDeviceStatus: String?,
    val audioDeviceStatus: String?,
    val halowConnectivityStatus: String?,
    val gyroStatus: String?,
    val accStatus: String?,
    val barometerStatus: String?,
    val escStatus: String?,
    val batteryUnitStatus: String?,
    val motorStatus: String?,
) {
    /** Yaw in degrees from Unreal-style quaternion (Z-up). */
    val headingDegrees: Double
        get() {
            val sinyCosp = 2.0 * (rotationW * rotationZ + rotationX * rotationY)
            val cosyCosp = 1.0 - 2.0 * (rotationY * rotationY + rotationZ * rotationZ)
            return Math.toDegrees(atan2(sinyCosp, cosyCosp))
        }
}

/**
 * Map + side-panel snapshot of a drone. Keeps pilot-critical fields without dumping every DTO key on the icon.
 */
data class DroneMapPose(
    val droneDisplayId: String,
    val deviceId: String = "",
    val droneIp: String? = null,
    val pixelX: Float,
    val pixelY: Float,
    val headingDegrees: Float,
    val batteryPercent: Int,
    val state: String?,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val confidence: Double = 0.0,
    val capturedAt: Long? = null,
    val motorStatus: String? = null,
    val escStatus: String? = null,
    val batteryUnitStatus: String? = null,
    val gyroStatus: String? = null,
    val accStatus: String? = null,
    val barometerStatus: String? = null,
    val videoDeviceStatus: String? = null,
    val micDeviceStatus: String? = null,
    val audioDeviceStatus: String? = null,
    val halowConnectivityStatus: String? = null,
)
