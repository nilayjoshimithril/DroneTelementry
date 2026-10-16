package com.guardianangel.mithrildefense.map

import com.guardianangel.mithrildefense.data.model.BuildingDto
import com.guardianangel.mithrildefense.data.model.FloorDto
import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.MapTileDto
import com.guardianangel.mithrildefense.data.model.MosaicAnnotationDto
import com.guardianangel.mithrildefense.data.model.OutdoorMapDto

const val OUTDOOR_BUILDING_ID = "outdoor"
const val OUTDOOR_FLOOR_ID = "Outdoor"

fun normalizeBuildingId(buildingId: String?): String {
    val trimmed = buildingId?.trim().orEmpty()
    return if (trimmed.isEmpty() || trimmed.equals("None", ignoreCase = true)) {
        OUTDOOR_BUILDING_ID
    } else {
        trimmed
    }
}

/** Mithril [normalizedForMapConfig]: resolve outdoor/indoor building + floor ids. */
fun resolveMapBuildingAndFloor(
    config: MapConfigResponseDto?,
    campusId: String?,
    rawBuildingId: String?,
    rawFloorId: String?,
): Pair<String, String> {
    val bid = normalizeBuildingId(rawBuildingId)
    val isOutdoor = bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) ||
        bid.equals(campusId, ignoreCase = true) ||
        findBuilding(config, bid) == null
    if (isOutdoor) return OUTDOOR_BUILDING_ID to OUTDOOR_FLOOR_ID
    val floor = rawFloorId?.trim()?.takeIf { it.isNotBlank() } ?: OUTDOOR_FLOOR_ID
    return bid to floor
}

fun MosaicAnnotationDto.normalizedForMapConfig(
    config: MapConfigResponseDto?,
    campusId: String?,
): MosaicAnnotationDto {
    val (building, floor) = resolveMapBuildingAndFloor(
        config = config,
        campusId = campusId,
        rawBuildingId = buildingId,
        rawFloorId = effectiveFloorId,
    )
    return copy(buildingId = building, floorId = floor)
}

fun findBuilding(config: MapConfigResponseDto?, buildingId: String?): BuildingDto? {
    val bid = normalizeBuildingId(buildingId)
    if (bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true)) return null
    return config?.buildings?.find { building ->
        building.id.equals(bid, ignoreCase = true) ||
            building.buildingId?.equals(bid, ignoreCase = true) == true
    }
}

/**
 * Resolves mosaic source for the selected building/floor.
 * Outdoor → [OutdoorMapDto]; indoor → matching [FloorDto]; fallback outdoor.
 */
sealed class MapLayerSource {
    data class Outdoor(val data: OutdoorMapDto) : MapLayerSource()
    data class Floor(val data: FloorDto) : MapLayerSource()
}

fun findMapLayer(
    config: MapConfigResponseDto?,
    buildingId: String?,
    floorId: String,
): MapLayerSource? {
    val bid = normalizeBuildingId(buildingId)
    if (bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) ||
        floorId.equals(OUTDOOR_FLOOR_ID, ignoreCase = true)
    ) {
        return config?.outdoor?.let { MapLayerSource.Outdoor(it) }
    }
    val building = findBuilding(config, bid)
    val floor = building?.floors?.find { floor ->
        floor.floorId.trim().equals(floorId.trim(), ignoreCase = true) ||
            floor.floorLabel.trim().equals(floorId.trim(), ignoreCase = true)
    }
    return when {
        floor != null -> MapLayerSource.Floor(floor)
        config?.outdoor != null -> MapLayerSource.Outdoor(config.outdoor)
        else -> null
    }
}

fun MapLayerSource.toTransform(): FloorPlanTransform = when (this) {
    is MapLayerSource.Outdoor -> outdoorTransform(data)
    is MapLayerSource.Floor -> floorTransform(data)
}

fun MapLayerSource.layerCols(): Int = when (this) {
    is MapLayerSource.Outdoor -> data.cols
    is MapLayerSource.Floor -> data.cols
}

fun MapLayerSource.layerRows(): Int = when (this) {
    is MapLayerSource.Outdoor -> data.rows
    is MapLayerSource.Floor -> data.rows
}

fun MapLayerSource.layerTiles(): List<MapTileDto> = when (this) {
    is MapLayerSource.Outdoor -> data.tiles
    is MapLayerSource.Floor -> data.tiles
}

fun MapLayerSource.layerTileWidth(): Int = when (this) {
    is MapLayerSource.Outdoor ->
        data.config.resolutionX.takeIf { it > 0 }
            ?: data.overview?.resolutionX?.takeIf { it > 0 }
            ?: 1920
    is MapLayerSource.Floor ->
        data.config.resolutionX.takeIf { it > 0 }
            ?: data.overview?.resolutionX?.takeIf { it > 0 }
            ?: 1920
}

fun MapLayerSource.layerTileHeight(): Int = when (this) {
    is MapLayerSource.Outdoor ->
        data.config.resolutionY.takeIf { it > 0 }
            ?: data.overview?.resolutionY?.takeIf { it > 0 }
            ?: 1080
    is MapLayerSource.Floor ->
        data.config.resolutionY.takeIf { it > 0 }
            ?: data.overview?.resolutionY?.takeIf { it > 0 }
            ?: 1080
}

fun MapLayerSource.layerOverviewUrl(): String? = when (this) {
    is MapLayerSource.Outdoor -> data.overview?.url?.takeIf { it.isNotBlank() }
    is MapLayerSource.Floor -> data.overview?.url?.takeIf { it.isNotBlank() }
}

fun floorTransform(floor: FloorDto): FloorPlanTransform {
    val scale = floor.unrealToPixels.toFloat().takeIf { it > 0 }
        ?: floor.overview?.unrealToPixels?.toFloat()
        ?: 1f
    val resX = floor.config.resolutionX.takeIf { it > 0 }
        ?: floor.overview?.resolutionX?.takeIf { it > 0 }
        ?: 1920
    val resY = floor.config.resolutionY.takeIf { it > 0 }
        ?: floor.overview?.resolutionY?.takeIf { it > 0 }
        ?: 1080
    return FloorPlanTransform(
        originX = floor.config.originX.toFloat(),
        originY = floor.config.originY.toFloat(),
        unrealToPixels = scale,
        worldWidth = floor.cols * resX.toFloat(),
        worldHeight = floor.rows * resY.toFloat(),
    )
}

fun floorsForBuilding(config: MapConfigResponseDto?, buildingId: String?): List<Pair<String, String>> {
    val bid = normalizeBuildingId(buildingId)
    if (bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true)) {
        return if (config?.outdoor != null) {
            listOf(OUTDOOR_FLOOR_ID to OUTDOOR_FLOOR_ID)
        } else {
            emptyList()
        }
    }
    val building = findBuilding(config, bid) ?: return emptyList()
    return building.floors.mapIndexed { index, floor ->
        val id = floor.floorId.ifBlank { OUTDOOR_FLOOR_ID }
        val label = floor.floorLabel.ifBlank { "Floor ${index + 1}" }
        id to label
    }
}

fun buildingPickerItems(config: MapConfigResponseDto?): List<Pair<String, String>> {
    if (config == null) return emptyList()
    val items = mutableListOf<Pair<String, String>>()
    if (config.outdoor != null) {
        items += OUTDOOR_BUILDING_ID to "Outdoor Map"
    }
    config.buildings.forEach { building ->
        val id = building.id.ifBlank { building.buildingId.orEmpty() }
        if (id.isNotBlank()) {
            items += id to building.name.ifBlank { "Building" }
        }
    }
    return items
}
