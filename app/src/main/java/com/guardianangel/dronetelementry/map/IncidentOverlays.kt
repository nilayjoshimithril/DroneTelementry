package com.guardianangel.dronetelementry.map

import com.guardianangel.dronetelementry.data.model.BuildingDto
import com.guardianangel.dronetelementry.data.model.ColorPathwayDto
import com.guardianangel.dronetelementry.data.model.MapConfigResponseDto
import com.guardianangel.dronetelementry.data.model.ThreatDto

/** Mithril [MosaicColorPathway.DEFAULT_THICKNESS] (Unreal units). */
const val DEFAULT_PATHWAY_THICKNESS = 20f

/**
 * Real-time color pathway (spline) in raw Unreal world units — Mithril [MosaicColorPathway].
 * [floorIds] is the union of every floor the pathway touches; [chunks] drives per-floor point
 * slicing so an indoor floor only draws the stretch of the spline that lies on it.
 */
data class ColorPathway(
    val id: String,
    val colorArgb: Int,
    val thickness: Float,
    val points: List<PointF>,
    val floorIds: List<String>,
    val chunks: List<PathwayChunk> = emptyList(),
)

/**
 * One `spline_chunks[]` entry: points `[previous endpoint..endpointIndex]` (inclusive, first
 * chunk starts at 0) lie on [floorIds] (already resolved to map-config floor ids).
 */
data class PathwayChunk(
    val endpointIndex: Int,
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
 * Point runs of this pathway to draw on the selected layer (Mithril
 * [MosaicColorPathway.pointSegmentsForFloor] with the floor guard enabled):
 * - outdoor → the full polyline (outdoor shows every pathway);
 * - indoor with chunk metadata → only chunks whose floor_ids include the selected floor;
 *   adjacent matching chunks stay one run, gaps split into separate runs so we never draw
 *   a jump across a stretch that lies on another floor;
 * - indoor without chunks (legacy `spline_floors`) → full polyline only if it lists the floor.
 */
fun ColorPathway.segmentsForLayer(
    config: MapConfigResponseDto?,
    selectedBuildingId: String?,
    selectedFloorId: String,
): List<List<PointF>> {
    if (points.size < 2) return emptyList()
    val bid = normalizeBuildingId(selectedBuildingId)
    if (bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) ||
        selectedFloorId.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)
    ) {
        return listOf(points)
    }
    val building = findBuilding(config, bid)
    fun onSelectedFloor(floorId: String): Boolean {
        if (floorId.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)) return false
        if (floorId.equals(selectedFloorId, ignoreCase = true)) return true
        return building != null && floorsReferToSame(building, floorId, selectedFloorId)
    }

    if (chunks.isEmpty()) {
        return if (floorIds.any(::onSelectedFloor)) listOf(points) else emptyList()
    }

    val segments = mutableListOf<List<PointF>>()
    var pending = mutableListOf<PointF>()
    fun flush() {
        if (pending.size >= 2) segments += pending.toList()
        pending = mutableListOf()
    }
    var rangeStart = 0
    for (chunk in chunks.sortedBy { it.endpointIndex }) {
        val rangeEnd = chunk.endpointIndex.coerceIn(0, points.lastIndex)
        if (rangeStart > rangeEnd) continue
        if (chunk.floorIds.any(::onSelectedFloor)) {
            // Adjacent chunks share their boundary point — don't duplicate it.
            val sliceStart = if (pending.isEmpty()) rangeStart else (rangeStart + 1).coerceAtMost(rangeEnd)
            if (sliceStart <= rangeEnd) pending.addAll(points.subList(sliceStart, rangeEnd + 1))
        } else {
            flush()
        }
        rangeStart = rangeEnd
    }
    flush()
    return segments
}

/** One [PathwayRender] per visible run (ids suffixed so each gets its own Path). */
fun ColorPathway.toRenders(
    transform: FloorPlanTransform,
    segments: List<List<PointF>>,
): List<PathwayRender> = segments.mapIndexed { index, run ->
    val pts = FloatArray(run.size * 2)
    run.forEachIndexed { i, p ->
        val px = unrealToFloorPlanPixel(p, transform)
        pts[i * 2] = px.x
        pts[i * 2 + 1] = px.y
    }
    PathwayRender(
        id = if (segments.size == 1) id else "$id#$index",
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
    // Legacy payloads put the floor once at the top (`spline_floors`) and send chunks with only
    // `chunk_end_point_index` — those chunks inherit the top-level floors.
    val legacyFloors = splineFloors.orEmpty()
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .map { resolveChunkFloorId(config, campusId, rawBuilding, it) }
        .distinct()
    val chunks = splineChunks.orEmpty().mapNotNull { chunk ->
        val end = chunk.endpointIndex ?: chunk.chunkEndPointIndex ?: return@mapNotNull null
        val chunkBuilding = chunk.buildingIds.orEmpty().firstOrNull { it.isNotBlank() } ?: rawBuilding
        PathwayChunk(
            endpointIndex = end,
            floorIds = chunk.floorIds.orEmpty()
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { resolveChunkFloorId(config, campusId, chunkBuilding, it) }
                .distinct()
                .ifEmpty { legacyFloors },
        )
    }.sortedBy { it.endpointIndex }
    val floorIds = chunks.flatMap { it.floorIds }.distinct()
        .ifEmpty { legacyFloors }
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
        chunks = chunks,
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

    // Label matches always win over id/level matches — mixing them in one pass let "Floor 1"
    // land on "FL 2" when that floor's (0-based) level was 1 or its UUID contained a "1".
    searchPool.find { normalize(it.floorLabel) == normalizedInput }?.let { return it.floorId }

    val numericInput = normalizedInput.filter { it.isDigit() }
    if (numericInput.isNotEmpty()) {
        // "Floor 1" ↔ "FL 1" / "Level 1" / "1st Floor": compare the label's number exactly.
        searchPool.find { it.floorLabel.filter(Char::isDigit) == numericInput }
            ?.let { return it.floorId }
    }

    searchPool.find { normalize(it.floorId) == normalizedInput }?.let { return it.floorId }
    searchPool.find { it.level?.toString() == normalizedInput }?.let { return it.floorId }

    if (building != null) return building.floors.firstOrNull()?.floorId ?: floorName
    return if (floorName.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)) OUTDOOR_FLOOR_ID else floorName
}
