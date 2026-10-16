package com.guardianangel.mithrildefense.map

import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.MosaicAnnotationDto
import com.guardianangel.mithrildefense.data.model.OutdoorMapDto
import kotlin.math.cos

data class PointF(val x: Float, val y: Float)

data class FloorPlanTransform(
    val originX: Float,
    val originY: Float,
    val unrealToPixels: Float,
    val worldWidth: Float,
    val worldHeight: Float,
)

/**
 * Port of Mithril GeoProjector — WGS84 lat/lng → Unreal → mosaic pixels.
 */
class GeoProjector(
    private val annotations: List<MosaicAnnotationDto>,
    private val originLatConfig: Double? = null,
    private val originLngConfig: Double? = null,
    private val campusUnrealX: Float? = null,
    private val campusUnrealY: Float? = null,
) {
    private var scaleX = 1f
    private var scaleY = 1f
    private var originLat = 0.0
    private var originLng = 0.0
    private var originX = 0f
    private var originY = 0f

    private val degreeToMeters = 111_111f
    private val metersToUnreal = 100f

    init {
        calibrate()
    }

    private fun calibrate() {
        val calibrationPoints = calibrationAnnotations(annotations)
        val annotationScale = computeAnnotationScale(calibrationPoints)
        val hasCampusConfig = originLatConfig != null && originLngConfig != null && originLatConfig != 0.0
        val hasCampusUnreal = campusUnrealX != null && campusUnrealY != null

        if (hasCampusConfig && hasCampusUnreal) {
            originLat = originLatConfig!!
            originLng = originLngConfig!!
            originX = campusUnrealX!!
            originY = campusUnrealY!!
            if (annotationScale != null) {
                scaleX = annotationScale.first
                scaleY = annotationScale.second
            } else {
                val unrealPerDegree = degreeToMeters * metersToUnreal
                val cosLat = cos(originLat * Math.PI / 180.0)
                scaleX = (unrealPerDegree * cosLat).toFloat()
                scaleY = -unrealPerDegree
            }
            return
        }

        val pair = findAnnotationPair(calibrationPoints)
        if (pair != null) {
            val (p1, p2) = pair
            val dLat = p2.lat!! - p1.lat!!
            val dLng = p2.lng!! - p1.lng!!
            scaleX = (p2.x - p1.x) / dLng.toFloat()
            scaleY = (p2.y - p1.y) / dLat.toFloat()
            originLat = p1.lat!!
            originLng = p1.lng!!
            originX = p1.x
            originY = p1.y
            return
        }

        if (calibrationPoints.isNotEmpty()) {
            val p = calibrationPoints[0]
            originLat = p.lat!!
            originLng = p.lng!!
            originX = p.x
            originY = p.y
            val unrealPerDegree = degreeToMeters * metersToUnreal
            val cosLat = cos(originLat * Math.PI / 180.0)
            scaleX = (unrealPerDegree * cosLat).toFloat()
            scaleY = -unrealPerDegree
        }
    }

    private fun findAnnotationPair(
        points: List<MosaicAnnotationDto>,
    ): Pair<MosaicAnnotationDto, MosaicAnnotationDto>? {
        if (points.size < 2) return null
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val a = points[i]
                val b = points[j]
                if (a.lat != b.lat && a.lng != b.lng && a.x != b.x && a.y != b.y) {
                    return a to b
                }
            }
        }
        return null
    }

    fun latLngToPoint(lat: Double, lng: Double): PointF {
        if (originLat == 0.0) return PointF(0f, 0f)
        val x = originX + (lng - originLng).toFloat() * scaleX
        val y = originY + (lat - originLat).toFloat() * scaleY
        return PointF(x, y)
    }

    val isCalibrated: Boolean get() = originLat != 0.0
}

fun outdoorTransform(outdoor: OutdoorMapDto): FloorPlanTransform {
    val scale = outdoor.unrealToPixels.toFloat().takeIf { it > 0 }
        ?: outdoor.overview?.unrealToPixels?.toFloat()
        ?: 1f
    val resX = outdoor.config.resolutionX.takeIf { it > 0 } ?: 1920
    val resY = outdoor.config.resolutionY.takeIf { it > 0 } ?: 1080
    return FloorPlanTransform(
        originX = outdoor.config.originX.toFloat(),
        originY = outdoor.config.originY.toFloat(),
        unrealToPixels = scale,
        worldWidth = outdoor.cols * resX.toFloat(),
        worldHeight = outdoor.rows * resY.toFloat(),
    )
}

fun unrealToFloorPlanPixel(unreal: PointF, transform: FloorPlanTransform): PointF =
    PointF(
        (unreal.x - transform.originX) * transform.unrealToPixels,
        (unreal.y - transform.originY) * transform.unrealToPixels,
    )

fun unrealFromFloorPlanPixel(pixel: PointF, transform: FloorPlanTransform): PointF =
    PointF(
        pixel.x / transform.unrealToPixels.coerceAtLeast(0.0001f) + transform.originX,
        pixel.y / transform.unrealToPixels.coerceAtLeast(0.0001f) + transform.originY,
    )

fun latLngToFloorPlanPixel(
    lat: Double,
    lng: Double,
    projector: GeoProjector,
    transform: FloorPlanTransform,
): PointF = unrealToFloorPlanPixel(projector.latLngToPoint(lat, lng), transform)

fun calibrationAnnotations(annotations: List<MosaicAnnotationDto>): List<MosaicAnnotationDto> =
    annotations.filter {
        it.type.uppercase() != "USER" &&
            it.type.uppercase() != "CAMPUS_ANCHOR" &&
            it.lat != null && it.lng != null && it.lat != 0.0
    }

fun computeAnnotationScale(points: List<MosaicAnnotationDto>): Pair<Float, Float>? {
    if (points.size < 2) return null
    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            val a = points[i]
            val b = points[j]
            if (a.lat != b.lat && a.lng != b.lng && a.x != b.x && a.y != b.y) {
                val dLat = b.lat!! - a.lat!!
                val dLng = b.lng!! - a.lng!!
                return (b.x - a.x) / dLng.toFloat() to (b.y - a.y) / dLat.toFloat()
            }
        }
    }
    return null
}

fun computeCampusUnrealPosition(
    campusLat: Double,
    campusLng: Double,
    annotations: List<MosaicAnnotationDto>,
    floorTransform: FloorPlanTransform?,
): Pair<Float, Float>? {
    val calibrationPoints = calibrationAnnotations(annotations)
    val scale = computeAnnotationScale(calibrationPoints)
    if (calibrationPoints.isNotEmpty() && scale != null) {
        val ref = calibrationPoints.first()
        val unrealX = ref.x + (campusLng - ref.lng!!).toFloat() * scale.first
        val unrealY = ref.y + (campusLat - ref.lat!!).toFloat() * scale.second
        return unrealX to unrealY
    }
    return floorTransform?.let { transform ->
        val centerPixelX = transform.worldWidth / 2f
        val centerPixelY = transform.worldHeight / 2f
        val unrealX = centerPixelX / transform.unrealToPixels + transform.originX
        val unrealY = centerPixelY / transform.unrealToPixels + transform.originY
        unrealX to unrealY
    }
}

fun createCampusGeoProjector(
    campusLat: Double?,
    campusLng: Double?,
    annotations: List<MosaicAnnotationDto>,
    outdoorTransform: FloorPlanTransform?,
): GeoProjector? {
    if (campusLat == null || campusLng == null || campusLat == 0.0 || campusLng == 0.0) return null
    val campusUnreal = computeCampusUnrealPosition(campusLat, campusLng, annotations, outdoorTransform)
    return GeoProjector(
        annotations = annotations,
        originLatConfig = campusLat,
        originLngConfig = campusLng,
        campusUnrealX = campusUnreal?.first,
        campusUnrealY = campusUnreal?.second,
    )
}

fun MapConfigResponseDto.campusLatLng(): Pair<Double, Double>? {
    val lat = campusLatitude?.toDoubleOrNull() ?: return null
    val lng = campusLongitude?.toDoubleOrNull() ?: return null
    if (lat == 0.0 || lng == 0.0) return null
    return lat to lng
}
