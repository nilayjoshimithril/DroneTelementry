package com.guardianangel.dronetelementry.domain.model

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
    /** Pilot (user id) flying this drone, when telemetry carries it. */
    val pilotId: String? = null,
    val pilotName: String? = null,
    /** Matched against the signed-in user's email to mark the drone "Mine". */
    val pilotEmail: String? = null,
    /** Pilot's display colour as sent (e.g. `#FF891C`); unparsed. */
    val pilotHexColor: String? = null,
    /** Telemetry `yaw` (degrees). When present it drives the marker direction. */
    val yawDegrees: Double? = null,
    /**
     * Direction of travel (Unreal yaw, degrees) from the last position to this one; null until the
     * drone has moved far enough to tell. Takes priority so the arrow points where it is going.
     */
    val courseDegrees: Double? = null,
) {
    /**
     * Marker heading in degrees (Unreal yaw: 0° = +X, clockwise on the map).
     * Priority: direction of travel → reported `yaw` → pose quaternion (Z-up).
     */
    val headingDegrees: Double
        get() {
            courseDegrees?.let { return it }
            yawDegrees?.let { return it + YAW_OFFSET_DEG }
            val sinyCosp = 2.0 * (rotationW * rotationZ + rotationX * rotationY)
            val cosyCosp = 1.0 - 2.0 * (rotationY * rotationY + rotationZ * rotationZ)
            return Math.toDegrees(atan2(sinyCosp, cosyCosp))
        }

    companion object {
        /**
         * Added to telemetry `yaw` before drawing. 0 assumes the drone's yaw shares Unreal's
         * zero (+X). If every arrow is off by the same angle (e.g. 90°), set it here.
         */
        const val YAW_OFFSET_DEG = 0.0
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
    /** Pilot (user id) flying this drone, when telemetry carries it. */
    val pilotId: String? = null,
    val pilotName: String? = null,
    /** Matched against the signed-in user's email to mark the drone "Mine". */
    val pilotEmail: String? = null,
    /** Pilot's display colour as sent (e.g. `#FF891C`); unparsed. */
    val pilotHexColor: String? = null,
    /** Flown by the signed-in user (pilot_email, else pilot_id). */
    val isMine: Boolean = false,
) {
    /**
     * Map label beside the marker: "Me (name)" / "Me" for the user's own drone, otherwise the
     * pilot's name, falling back to the drone's display id.
     */
    val mapLabel: String
        get() {
            val name = pilotName?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                isMine && name != null -> "Me ($name)"
                isMine -> "Me"
                name != null -> name
                else -> droneDisplayId
            }
        }
}
