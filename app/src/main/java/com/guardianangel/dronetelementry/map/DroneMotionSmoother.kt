package com.guardianangel.dronetelementry.map

import com.guardianangel.dronetelementry.domain.model.DroneMapPose

/**
 * Turns sparse drone telemetry (each drone reports every few seconds to ~30 s) into continuous
 * marker motion: every new target position starts a glide from wherever the marker is drawn
 * right now, so the drone slides to the new spot instead of teleporting.
 *
 * Glide length follows the drone's own update interval (so a steady stream reads as continuous
 * flight), clamped to [[MIN_GLIDE_MS], [MAX_GLIDE_MS]] so the marker never lags reality by more
 * than [MAX_GLIDE_MS]. Heading turns along the shortest arc.
 *
 * Not thread-safe: call from the main thread only.
 */
class DroneMotionSmoother {

    private class Glide(
        var target: DroneMapPose,
        var fromX: Float,
        var fromY: Float,
        var fromHeading: Float,
        var startMs: Long,
        var durationMs: Long,
        var lastTargetMs: Long,
    )

    private val glides = LinkedHashMap<String, Glide>()

    /** True while at least one marker is still between its old and new position. */
    fun isAnimating(nowMs: Long): Boolean =
        glides.values.any { nowMs - it.startMs < it.durationMs }

    /** Feed the latest target poses. Unknown drones appear in place; vanished drones are dropped. */
    fun setTargets(poses: Map<String, DroneMapPose>, nowMs: Long) {
        glides.keys.retainAll(poses.keys)
        poses.forEach { (id, pose) ->
            val glide = glides[id]
            if (glide == null) {
                glides[id] = Glide(
                    target = pose,
                    fromX = pose.pixelX,
                    fromY = pose.pixelY,
                    fromHeading = pose.headingDegrees,
                    startMs = nowMs,
                    durationMs = 0L,
                    lastTargetMs = nowMs,
                )
                return@forEach
            }
            val old = glide.target
            val moved = old.pixelX != pose.pixelX ||
                old.pixelY != pose.pixelY ||
                old.headingDegrees != pose.headingDegrees
            if (!moved) {
                // Same position re-published (state/battery change): keep the glide running.
                glide.target = pose
                return@forEach
            }
            // Start from what is on screen now, so a mid-glide update never jumps.
            val p = progress(glide, nowMs)
            glide.fromX = lerp(glide.fromX, old.pixelX, p)
            glide.fromY = lerp(glide.fromY, old.pixelY, p)
            glide.fromHeading = lerpAngle(glide.fromHeading, old.headingDegrees, p)
            glide.durationMs = (nowMs - glide.lastTargetMs).coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
            glide.startMs = nowMs
            glide.lastTargetMs = nowMs
            glide.target = pose
        }
    }

    /** Poses as they should be drawn at [nowMs]; non-position fields come from the latest target. */
    fun current(nowMs: Long): Map<String, DroneMapPose> =
        glides.mapValues { (_, glide) ->
            val p = progress(glide, nowMs)
            val target = glide.target
            if (p >= 1f) {
                target
            } else {
                target.copy(
                    pixelX = lerp(glide.fromX, target.pixelX, p),
                    pixelY = lerp(glide.fromY, target.pixelY, p),
                    headingDegrees = lerpAngle(glide.fromHeading, target.headingDegrees, p),
                )
            }
        }

    /** Layer switched: pixel coordinates belong to a different floor plan, so snap next time. */
    fun reset() = glides.clear()

    private fun progress(glide: Glide, nowMs: Long): Float {
        if (glide.durationMs <= 0L) return 1f
        val t = ((nowMs - glide.startMs).toFloat() / glide.durationMs).coerceIn(0f, 1f)
        // Long legs (sparse live telemetry) ease in/out so they start and settle softly.
        // Short legs (frequent updates) stay linear, otherwise each restart would stutter.
        return if (glide.durationMs >= EASE_MIN_GLIDE_MS) t * t * (3f - 2f * t) else t
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var d = ((to - from) % 360f + 360f) % 360f
        if (d > 180f) d -= 360f
        return from + d * t
    }

    companion object {
        /** Floor for very frequent updates (demo drones tick every frame or so). */
        const val MIN_GLIDE_MS = 120L
        /**
         * Ceiling on glide length = the most the drawn marker can trail the real position.
         * Raise it (e.g. to 30_000) for fully continuous motion with ~30 s telemetry, at the cost
         * of showing where the drone was rather than where it is.
         */
        const val MAX_GLIDE_MS = 3_000L
        private const val EASE_MIN_GLIDE_MS = 1_000L
    }
}
