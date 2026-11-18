package com.guardianangel.dronetelementry.data.socket

import com.guardianangel.dronetelementry.data.model.DronePosePositionDto
import com.guardianangel.dronetelementry.data.model.DronePoseRotationDto

/**
 * Converts `on-drone-telemetry` poses from the drone's frame (Y-up, meters) into the
 * frame the map pipeline expects (Unreal: Z-up, centimeters for X/Y, meters for altitude).
 *
 * Wire: x, z = floor plane (m), y = height (m).
 * App:  positionX/positionY = Unreal cm on the floor plan, positionZ = altitude (m).
 *
 * If drones appear mirrored on the map after a field check, flip [FLOOR_Y_SIGN].
 */
object PoseFrameConverter {

    /** Meters → Unreal units (cm). */
    const val METERS_TO_UNREAL = 100.0

    /** Sign applied to wire Z when it becomes Unreal Y. Flip to -1.0 if the map is mirrored top↔bottom. */
    const val FLOOR_Y_SIGN = 1.0

    data class Position(val x: Double, val y: Double, val z: Double)
    data class Rotation(val x: Double, val y: Double, val z: Double, val w: Double)

    fun position(p: DronePosePositionDto): Position = Position(
        x = p.x * METERS_TO_UNREAL,
        y = FLOOR_Y_SIGN * p.z * METERS_TO_UNREAL,
        z = p.y,
    )

    /**
     * Remaps the quaternion with the same axis change as [position]: M = (x, s·z, y).
     * A quaternion's vector part is an axial vector, so it transforms as det(M)·M·v,
     * with det(M) = -s (the y/z swap flips handedness).
     */
    fun rotation(r: DronePoseRotationDto): Rotation {
        val s = FLOOR_Y_SIGN
        val det = -s
        return Rotation(
            x = det * r.x,
            y = det * s * r.z,
            z = det * r.y,
            w = r.w,
        )
    }
}
