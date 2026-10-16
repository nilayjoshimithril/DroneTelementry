package com.guardianangel.mithrildefense.map

import com.guardianangel.mithrildefense.data.model.BuildingDto
import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.domain.model.DroneTelemetry
import kotlin.math.abs

/** Mithril CampusMapGeo — naive continuous altitude → floor level. */
const val METERS_PER_FLOOR = 3.5

/** Margin (Unreal units) around a floor-plan footprint when testing containment. */
private const val BUILDING_FOOTPRINT_MARGIN_UNREAL = 400f

data class UnrealFootprint(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    fun contains(x: Float, y: Float, margin: Float = BUILDING_FOOTPRINT_MARGIN_UNREAL): Boolean =
        x in (minX - margin)..(maxX + margin) &&
            y in (minY - margin)..(maxY + margin)
}

fun unrealFootprintFromTransform(transform: FloorPlanTransform): UnrealFootprint {
    val extentX = transform.worldWidth / transform.unrealToPixels.coerceAtLeast(0.0001f)
    val extentY = transform.worldHeight / transform.unrealToPixels.coerceAtLeast(0.0001f)
    return UnrealFootprint(
        minX = transform.originX,
        maxX = transform.originX + extentX,
        minY = transform.originY,
        maxY = transform.originY + extentY,
    )
}

private fun groundFloorForFootprint(building: BuildingDto) =
    building.floors.minByOrNull { it.level ?: Int.MAX_VALUE } ?: building.floors.firstOrNull()

fun isDroneInBuilding(
    unrealX: Float,
    unrealY: Float,
    building: BuildingDto,
): Boolean {
    val ground = groundFloorForFootprint(building) ?: return false
    val footprint = unrealFootprintFromTransform(floorTransform(ground))
    return footprint.contains(unrealX, unrealY)
}

/**
 * Maps altitude (meters, Unreal Z) onto a floor id for [building], same rules as
 * Mithril [estimateUserFloorId] / [floorIdForLevel].
 */
fun estimateFloorIdFromAltitude(building: BuildingDto, altitudeMeters: Double): String? {
    val floors = building.floors
    if (floors.isEmpty()) return null
    val level = (abs(altitudeMeters) / METERS_PER_FLOOR).toInt().coerceAtLeast(0)
    val withLevel = floors.filter { it.level != null }
    if (withLevel.isNotEmpty()) {
        return withLevel.minByOrNull { abs((it.level ?: 0) - level) }?.floorId
    }
    return floors.getOrNull(level.coerceIn(0, floors.lastIndex))?.floorId
}

fun floorsReferToSame(
    building: BuildingDto,
    floorIdA: String?,
    floorIdB: String?,
): Boolean {
    val a = floorIdA?.trim().orEmpty()
    val b = floorIdB?.trim().orEmpty()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return true
    val floorA = building.floors.find {
        it.floorId.equals(a, ignoreCase = true) || it.floorLabel.equals(a, ignoreCase = true)
    }
    val floorB = building.floors.find {
        it.floorId.equals(b, ignoreCase = true) || it.floorLabel.equals(b, ignoreCase = true)
    }
    return floorA != null && floorA == floorB
}

/**
 * Resolves which outdoor/building + floor a drone currently belongs on — used for map
 * follow (Mithril threat auto-navigation) and layer filtering.
 */
fun resolveDroneMapPlacement(
    telemetry: DroneTelemetry,
    config: MapConfigResponseDto?,
    campusId: String?,
): Pair<String, String> {
    val explicitBuilding = telemetry.buildingId?.trim()?.takeIf { it.isNotBlank() }
    val explicitFloor = telemetry.floorId?.trim()?.takeIf { it.isNotBlank() }
    if (explicitBuilding != null || explicitFloor != null) {
        val (building, floor) = resolveMapBuildingAndFloor(
            config = config,
            campusId = campusId,
            rawBuildingId = explicitBuilding,
            rawFloorId = explicitFloor,
        )
        if (building.equals(OUTDOOR_BUILDING_ID, ignoreCase = true)) {
            return OUTDOOR_BUILDING_ID to OUTDOOR_FLOOR_ID
        }
        if (explicitFloor != null) return building to floor
        val buildingDto = findBuilding(config, building)
        val estimated = buildingDto?.let { estimateFloorIdFromAltitude(it, telemetry.positionZ) }
        return building to (estimated ?: floor)
    }

    val unrealX = telemetry.positionX.toFloat()
    val unrealY = telemetry.positionY.toFloat()
    val containing = config?.buildings.orEmpty().mapNotNull { building ->
        if (!isDroneInBuilding(unrealX, unrealY, building)) return@mapNotNull null
        val id = building.id.ifBlank { building.buildingId.orEmpty() }
        if (id.isBlank()) return@mapNotNull null
        val ground = building.floors.minByOrNull { it.level ?: Int.MAX_VALUE }
            ?: building.floors.firstOrNull()
            ?: return@mapNotNull null
        val fp = unrealFootprintFromTransform(floorTransform(ground))
        val area = (fp.maxX - fp.minX).toDouble() * (fp.maxY - fp.minY)
        Triple(id, building, area)
    }
    val match = containing.minByOrNull { it.third }
    if (match == null) return OUTDOOR_BUILDING_ID to OUTDOOR_FLOOR_ID
    val floorId = estimateFloorIdFromAltitude(match.second, telemetry.positionZ)
        ?: match.second.floors.firstOrNull()?.floorId
        ?: OUTDOOR_FLOOR_ID
    return match.first to floorId
}

/**
 * Outdoor → every drone. Indoor → only drones in that building on the selected floor.
 *
 * Prefers explicit telemetry `building_id` / `floor_id` when present; otherwise infers
 * building from floor-plan footprint and floor from altitude (Mithril METERS_PER_FLOOR).
 */
fun shouldShowDroneOnLayer(
    telemetry: DroneTelemetry,
    config: MapConfigResponseDto?,
    campusId: String?,
    selectedBuildingId: String?,
    selectedFloorId: String,
): Boolean {
    val selectedBid = normalizeBuildingId(selectedBuildingId)
    val isOutdoorView = selectedBid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) ||
        selectedFloorId.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)
    if (isOutdoorView) return true

    val building = findBuilding(config, selectedBid) ?: return false

    val explicitBuilding = telemetry.buildingId?.trim()?.takeIf { it.isNotBlank() }
    val explicitFloor = telemetry.floorId?.trim()?.takeIf { it.isNotBlank() }
    if (explicitBuilding != null || explicitFloor != null) {
        val (resolvedBuilding, resolvedFloor) = resolveMapBuildingAndFloor(
            config = config,
            campusId = campusId,
            rawBuildingId = explicitBuilding ?: selectedBid,
            rawFloorId = explicitFloor,
        )
        if (!resolvedBuilding.equals(selectedBid, ignoreCase = true)) return false
        if (explicitFloor != null) {
            return floorsReferToSame(building, resolvedFloor, selectedFloorId)
        }
        val estimatedFloorId = estimateFloorIdFromAltitude(building, telemetry.positionZ)
            ?: return true
        return floorsReferToSame(building, estimatedFloorId, selectedFloorId)
    }

    val unrealX = telemetry.positionX.toFloat()
    val unrealY = telemetry.positionY.toFloat()
    if (!isDroneInBuilding(unrealX, unrealY, building)) return false

    val estimatedFloorId = estimateFloorIdFromAltitude(building, telemetry.positionZ)
        ?: return true
    return floorsReferToSame(building, estimatedFloorId, selectedFloorId)
}
