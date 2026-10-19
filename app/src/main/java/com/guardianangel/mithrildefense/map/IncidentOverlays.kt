package com.guardianangel.mithrildefense.map

import com.guardianangel.mithrildefense.data.model.BuildingDto
import com.guardianangel.mithrildefense.data.model.ColorPathwayDto
import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.ThreatDto

/** Mithril [MosaicColorPathway.DEFAULT_THICKNESS] (Unreal units). */
const val DEFAULT_PATHWAY_THICKNESS = 20f

/**
 * Real-time color pathway (spline) in raw Unreal world units — Mithril [MosaicColorPathway].
 * [floorIds] is kept for when per-floor slicing is re-enabled upstream.
 */
data class ColorPathway(
    val id: String,
    val colorArgb: Int,
    val thickness: Float,
    val points: List<PointF>,
    val floorIds: List<String>,
)

/** Real-time threat resolved onto a map layer — Mithril THREAT [MosaicAnnotation]. */
data class MapThreat(
    val id: String,
    val name: String,
    val buildingId: String,
    val floorId: String,
    val unrealX: Float,
    val unrealY: Float,
    val timestamp: String?,
)

/** Render-ready pathway for the active layer: world-pixel polyline + stroke in world px. */
data class PathwayRender(
    val id: String,
    val colorArgb: Int,
    val strokeWorldPx: Float,
    val points: FloatArray,
)

data class ThreatMarker(
    val id: String,
    val label: String,
    val pixelX: Float,
    val pixelY: Float,
)

/**
 * Mithril [resolvePathwayColorLong] — `spline_color` index → ARGB (70% alpha).
 * 0 red, 1 yellow, 2 green, unknown → red.
 */
fun pathwayColorArgb(colorIndex: Int?): Int = when (colorIndex) {
    1 -> 0xB3FFE001.toInt()
    2 -> 0xB303FF00.toInt()
    else -> 0xB3FF0002.toInt()
}

/**
 * Mithril [MosaicColorPathway.isVisibleOnFloor]: the floor-id guard is disabled upstream,
 * so every pathway with a drawable polyline shows on outdoor and every indoor floor.
 */
fun ColorPathway.isVisibleOnLayer(): Boolean = points.size >= 2

fun ColorPathway.toRender(transform: FloorPlanTransform): PathwayRender {
    val pts = FloatArray(points.size * 2)
    points.forEachIndexed { i, p ->
        val px = unrealToFloorPlanPixel(p, transform)
        pts[i * 2] = px.x
        pts[i * 2 + 1] = px.y
    }
    return PathwayRender(
        id = id,
        colorArgb = colorArgb,
        strokeWorldPx = thickness * transform.unrealToPixels,
        points = pts,
    )
}

/**
 * Mithril [MapViewModel.resolveColorPathway]: chunk `floor_ids` (current API) win over legacy
 * `spline_floors`; no floors → outdoor-only.
 */
fun ColorPathwayDto.toColorPathway(
    config: MapConfigResponseDto?,
    campusId: String?,
): ColorPathway? {
    val id = splineId?.takeIf { it.isNotBlank() } ?: return null
    val rawBuilding = splineChunks.orEmpty()
        .firstNotNullOfOrNull { chunk -> chunk.buildingIds.orEmpty().firstOrNull { it.isNotBlank() } }
        ?: splineBuildings?.firstOrNull()
    val chunkFloors = splineChunks.orEmpty()
        .flatMap { it.floorIds.orEmpty() }
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    val rawFloors = chunkFloors.ifEmpty {
        splineFloors.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    }
    val floorIds = rawFloors
        .map { resolveChunkFloorId(config, campusId, rawBuilding, it) }
        .distinct()
        .ifEmpty { listOf(OUTDOOR_FLOOR_ID) }
    val points = splinePoints.orEmpty().mapNotNull { p ->
        val x = p.x?.toFloat() ?: return@mapNotNull null
        val y = p.y?.toFloat() ?: return@mapNotNull null
        PointF(x, y)
    }
    return ColorPathway(
        id = id,
        colorArgb = pathwayColorArgb(splineColor),
        thickness = splineThickness?.toFloat() ?: DEFAULT_PATHWAY_THICKNESS,
        points = points,
        floorIds = floorIds,
    )
}

/** Mithril [MapViewModel.buildThreatAnnotation]. */
fun ThreatDto.toMapThreat(config: MapConfigResponseDto?, campusId: String?): MapThreat {
    val rawBuilding = building?.firstOrNull()
    val buildingId = resolveOverlayBuildingId(config, campusId, rawBuilding)
    val floorId = if (buildingId == OUTDOOR_BUILDING_ID) {
        OUTDOOR_FLOOR_ID
    } else {
        resolveOverlayFloorId(config, campusId, rawBuilding, floors?.firstOrNull())
    }
    return MapThreat(
        id = threatId,
        name = threatName?.takeIf { it.isNotBlank() } ?: "Threat",
        buildingId = buildingId,
        floorId = floorId,
        unrealX = x ?: 0f,
        unrealY = y ?: 0f,
        timestamp = timestamp,
    )
}

/**
 * Mithril MithrilMapComponent extra-annotation filter: outdoor shows every threat; indoor
 * shows threats whose floor matches the selected floor (or that carry no floor).
 */
fun MapThreat.isVisibleOnLayer(
    config: MapConfigResponseDto?,
    selectedBuildingId: String?,
    selectedFloorId: String,
): Boolean {
    val bid = normalizeBuildingId(selectedBuildingId)
    if (bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) ||
        selectedFloorId.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)
    ) {
        return true
    }
    if (floorId.isBlank()) return true
    if (floorId.equals(selectedFloorId, ignoreCase = true)) return true
    val building = findBuilding(config, bid) ?: return false
    return floorsReferToSame(building, floorId, selectedFloorId)
}

private fun BuildingDto.canonicalId(): String = id.ifBlank { buildingId.orEmpty() }

/** Mithril [findMapConfigBuilding]: id, building_id, or display name. */
private fun findOverlayBuilding(config: MapConfigResponseDto?, rawBuildingId: String?): BuildingDto? {
    val target = rawBuildingId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return findBuilding(config, target)
        ?: config?.buildings?.find { it.name.trim().equals(target, ignoreCase = true) }
}

/** Mithril [resolveMapBuildingId]: campus id / blank / unknown → outdoor. */
private fun resolveOverlayBuildingId(
    config: MapConfigResponseDto?,
    campusId: String?,
    rawBuildingId: String?,
): String {
    val raw = rawBuildingId?.trim()
    if (raw.isNullOrBlank() ||
        raw.equals("None", ignoreCase = true) ||
        raw.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) ||
        (!campusId.isNullOrBlank() && raw.equals(campusId.trim(), ignoreCase = true))
    ) {
        return OUTDOOR_BUILDING_ID
    }
    return findOverlayBuilding(config, raw)?.canonicalId()?.takeIf { it.isNotBlank() }
        ?: OUTDOOR_BUILDING_ID
}

/**
 * Mithril [resolveChunkFloorId]: exact floor UUID / label anywhere on campus first, so
 * multi-building pathways are not forced onto the first building's floors.
 */
private fun resolveChunkFloorId(
    config: MapConfigResponseDto?,
    campusId: String?,
    rawBuildingId: String?,
    rawFloorId: String,
): String {
    if (rawFloorId.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)) return OUTDOOR_FLOOR_ID
    config?.buildings.orEmpty().flatMap { it.floors }.find {
        it.floorId.trim().equals(rawFloorId, ignoreCase = true) ||
            it.floorLabel.trim().equals(rawFloorId, ignoreCase = true)
    }?.let { return it.floorId }
    return resolveOverlayFloorId(config, campusId, rawBuildingId, rawFloorId)
}

/**
 * Mithril [MapViewModel.resolveFloorIdRaw]: maps a socket floor name ("Floor 2", "Ground",
 * "Roof", a UUID…) onto a map-config floor id — exact match, roof/basement, normalized
 * match, then numeric fallback.
 */
private fun resolveOverlayFloorId(
    config: MapConfigResponseDto?,
    campusId: String?,
    rawBuildingId: String?,
    rawFloorName: String?,
): String {
    val floorName = rawFloorName?.trim().orEmpty()
    if (resolveOverlayBuildingId(config, campusId, rawBuildingId) == OUTDOOR_BUILDING_ID) {
        return OUTDOOR_FLOOR_ID
    }
    if (config == null) return floorName

    fun normalize(s: String): String {
        val lower = s.lowercase()
        if (lower.contains("ground")) return "0"
        if (lower.contains("basement")) return "b"
        if (lower.contains("roof")) return "roof"
        if (lower.contains("outdoor")) return "outdoor"
        return lower
            .replace("floor", "")
            .replace("fl", "")
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
    }

    val normalizedInput = normalize(floorName)
    val building = findOverlayBuilding(config, rawBuildingId)
    val searchPool = building?.floors ?: config.buildings.flatMap { it.floors }

    searchPool.find {
        it.floorLabel.trim().equals(floorName, ignoreCase = true) ||
            it.floorId.trim().equals(floorName, ignoreCase = true)
    }?.let { return it.floorId }

    if (normalizedInput == "roof") {
        searchPool.find { normalize(it.floorLabel) == "roof" }?.let { return it.floorId }
    }
    if (normalizedInput == "b") {
        searchPool.find { (it.level ?: 0) < 0 }?.let { return it.floorId }
    }

    searchPool.find {
        normalize(it.floorLabel) == normalizedInput ||
            normalize(it.floorId) == normalizedInput ||
            it.level?.toString() == normalizedInput
    }?.let { return it.floorId }

    val numericInput = normalizedInput.filter { it.isDigit() }
    if (numericInput.isNotEmpty()) {
        searchPool.find {
            it.floorId.contains(numericInput) || it.floorLabel.contains(numericInput)
        }?.let { return it.floorId }
    }

    if (building != null) return building.floors.firstOrNull()?.floorId ?: floorName
    return if (floorName.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)) OUTDOOR_FLOOR_ID else floorName
}
