package com.guardianangel.dronetelementry.map

import com.guardianangel.dronetelementry.domain.model.DroneMapPose
import kotlin.math.abs

/**
 * Turns sparse drone telemetry (each drone reports every few seconds to ~30 s) into continuous
 * marker motion. Every new target runs in two phases, starting from wherever the marker is drawn
 * right now:
 *
 * 1. **Turn**: the marker rotates in place to the new heading (shortest arc), quickly, at
 *    [TURN_DEG_PER_MS], capped at [MAX_TURN_MS].
 * 2. **Move**: the marker then slides to the new position.
 *
 * The move time is the drone's own update interval minus the turn time, clamped to
 * [[MIN_GLIDE_MS], [MAX_GLIDE_MS]], so a steady stream reads as continuous flight and the
 * marker never trails reality by much more than [MAX_GLIDE_MS] + [MAX_TURN_MS].
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
        var turnMs: Long,
        var moveMs: Long,
        var lastTargetMs: Long,
    )

    private val glides = LinkedHashMap<String, Glide>()

    /** True while at least one marker is still turning or moving. */
    fun isAnimating(nowMs: Long): Boolean =
        glides.values.any { nowMs - it.startMs < it.turnMs + it.moveMs }

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
                    turnMs = 0L,
                    moveMs = 0L,
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
            val turnP = turnProgress(glide, nowMs)
            val moveP = moveProgress(glide, nowMs)
            glide.fromX = lerp(glide.fromX, old.pixelX, moveP)
            glide.fromY = lerp(glide.fromY, old.pixelY, moveP)
            glide.fromHeading = lerpAngle(glide.fromHeading, old.headingDegrees, turnP)

            val turnAngle = abs(shortestDelta(glide.fromHeading, pose.headingDegrees))
            val turnMs = (turnAngle / TURN_DEG_PER_MS).toLong().coerceAtMost(MAX_TURN_MS)
            val interval = nowMs - glide.lastTargetMs
            glide.turnMs = turnMs
            glide.moveMs = (interval - turnMs).coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
            glide.startMs = nowMs
            glide.lastTargetMs = nowMs
            glide.target = pose
        }
    }

    /** Poses as they should be drawn at [nowMs]; non-position fields come from the latest target. */
    fun current(nowMs: Long): Map<String, DroneMapPose> =
        glides.mapValues { (_, glide) ->
            val turnP = turnProgress(glide, nowMs)
            val moveP = moveProgress(glide, nowMs)
            val target = glide.target
            if (turnP >= 1f && moveP >= 1f) {
                target
            } else {
                target.copy(
                    pixelX = lerp(glide.fromX, target.pixelX, moveP),
                    pixelY = lerp(glide.fromY, target.pixelY, moveP),
                    headingDegrees = lerpAngle(glide.fromHeading, target.headingDegrees, turnP),
                )
            }
        }

    /** Layer switched: pixel coordinates belong to a different floor plan, so snap next time. */
    fun reset() = glides.clear()

    /** Phase 1: rotate in place. Eased so the turn starts and stops softly. */
    private fun turnProgress(glide: Glide, nowMs: Long): Float {
        if (glide.turnMs <= 0L) return 1f
        val t = ((nowMs - glide.startMs).toFloat() / glide.turnMs).coerceIn(0f, 1f)
        return smoothstep(t)
    }

    /** Phase 2: slide to the new position once the turn is done. */
    private fun moveProgress(glide: Glide, nowMs: Long): Float {
        val elapsed = nowMs - glide.startMs - glide.turnMs
        if (glide.moveMs <= 0L) return if (elapsed >= 0L) 1f else 0f
        val t = (elapsed.toFloat() / glide.moveMs).coerceIn(0f, 1f)
        // Long legs (sparse live telemetry) ease in/out so they start and settle softly.
        // Short legs (frequent updates) stay linear, otherwise each restart would stutter.
        return if (glide.moveMs >= EASE_MIN_GLIDE_MS) smoothstep(t) else t
    }

    private fun smoothstep(t: Float) = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun shortestDelta(from: Float, to: Float): Float {
        var d = ((to - from) % 360f + 360f) % 360f
        if (d > 180f) d -= 360f
        return d
    }

    private fun lerpAngle(from: Float, to: Float, t: Float): Float =
        from + shortestDelta(from, to) * t

    companion object {
        /** Floor for very frequent updates (demo drones tick every frame or so). */
        const val MIN_GLIDE_MS = 120L
        /**
         * Ceiling on move length = roughly the most the drawn marker can trail the real position.
         * Raise it (e.g. to 30_000) for fully continuous motion with ~30 s telemetry, at the cost
         * of showing where the drone was rather than where it is.
         */
        const val MAX_GLIDE_MS = 3_000L
        /** Turn speed: 0.6°/ms = a half turn (180°) in 300 ms. Raise for a snappier turn. */
        const val TURN_DEG_PER_MS = 0.6f
        /** Longest a turn may take, however large the angle. */
        const val MAX_TURN_MS = 350L
        private const val EASE_MIN_GLIDE_MS = 1_000L
    }
}
