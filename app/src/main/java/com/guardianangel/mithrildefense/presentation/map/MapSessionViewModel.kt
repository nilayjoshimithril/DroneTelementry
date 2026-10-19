package com.guardianangel.mithrildefense.presentation.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.MapTileDto
import com.guardianangel.mithrildefense.data.model.MosaicAnnotationDto
import com.guardianangel.mithrildefense.data.socket.SocketEvent
import com.guardianangel.mithrildefense.data.socket.SocketHelper
import com.guardianangel.mithrildefense.domain.interact.MapInteract
import com.guardianangel.mithrildefense.domain.model.DroneMapPose
import com.guardianangel.mithrildefense.domain.model.DroneTelemetry
import com.guardianangel.mithrildefense.map.ColorPathway
import com.guardianangel.mithrildefense.map.FloorPlanTransform
import com.guardianangel.mithrildefense.map.GeoProjector
import com.guardianangel.mithrildefense.map.METERS_PER_FLOOR
import com.guardianangel.mithrildefense.map.MapThreat
import com.guardianangel.mithrildefense.map.OUTDOOR_BUILDING_ID
import com.guardianangel.mithrildefense.map.OUTDOOR_FLOOR_ID
import com.guardianangel.mithrildefense.map.PathwayRender
import com.guardianangel.mithrildefense.map.PointF
import com.guardianangel.mithrildefense.map.ThreatMarker
import com.guardianangel.mithrildefense.map.buildingPickerItems
import com.guardianangel.mithrildefense.map.campusLatLng
import com.guardianangel.mithrildefense.map.createCampusGeoProjector
import com.guardianangel.mithrildefense.map.findBuilding
import com.guardianangel.mithrildefense.map.findMapLayer
import com.guardianangel.mithrildefense.map.floorsForBuilding
import com.guardianangel.mithrildefense.map.floorsReferToSame
import com.guardianangel.mithrildefense.map.isVisibleOnLayer
import com.guardianangel.mithrildefense.map.layerCols
import com.guardianangel.mithrildefense.map.layerOverviewUrl
import com.guardianangel.mithrildefense.map.layerRows
import com.guardianangel.mithrildefense.map.layerTileHeight
import com.guardianangel.mithrildefense.map.layerTileWidth
import com.guardianangel.mithrildefense.map.layerTiles
import com.guardianangel.mithrildefense.map.normalizeBuildingId
import com.guardianangel.mithrildefense.map.normalizedForMapConfig
import com.guardianangel.mithrildefense.map.outdoorTransform
import com.guardianangel.mithrildefense.map.resolveDroneMapPlacement
import com.guardianangel.mithrildefense.map.shouldShowDroneOnLayer
import com.guardianangel.mithrildefense.map.toColorPathway
import com.guardianangel.mithrildefense.map.toMapThreat
import com.guardianangel.mithrildefense.map.toRender
import com.guardianangel.mithrildefense.map.toTransform
import com.guardianangel.mithrildefense.map.unrealFromFloorPlanPixel
import com.guardianangel.mithrildefense.map.unrealToFloorPlanPixel
import com.guardianangel.mithrildefense.utils.AppLog
import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class MosaicLayerConfig(
    val cols: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val tiles: List<MapTileDto>,
    val overviewUrl: String?,
    val layerKey: String,
)

data class BuildingListItem(
    val id: String,
    val name: String,
    val floorCount: Int,
)

data class FloorListItem(
    val id: String,
    val label: String,
)

data class MapSessionUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Campus has no outdoor/building mosaic (Mithril "Map is Unavailable"). */
    val mapUnavailable: Boolean = false,
    val socketConnected: Boolean = false,
    val statusMessage: String = "Connecting…",
    val campusName: String = "",
    val showBuildingPicker: Boolean = true,
    val buildings: List<BuildingListItem> = emptyList(),
    val floors: List<FloorListItem> = emptyList(),
    val selectedBuildingId: String? = null,
    val selectedBuildingName: String = "",
    val selectedFloorId: String = OUTDOOR_FLOOR_ID,
    val selectedFloorLabel: String = OUTDOOR_FLOOR_ID,
    val mosaic: MosaicLayerConfig? = null,
    val selectedDroneId: String? = null,
    /** Mithril MapThreatOverlay — "Nth Threat Identified", auto-hides after a few seconds. */
    val threatBanner: ThreatBannerUi? = null,
    /** One-shot request to center the map on a world-pixel point (e.g. locate threat). */
    val mapFocus: MapFocus? = null,
)

data class ThreatBannerUi(
    val threatCount: Int,
    val threatId: String,
)

data class MapFocus(
    val layerKey: String,
    val pixelX: Float,
    val pixelY: Float,
    val nonce: Long,
)

/** Color pathways + threat markers projected onto the active layer. */
data class MapOverlays(
    val pathways: List<PathwayRender> = emptyList(),
    val threats: List<ThreatMarker> = emptyList(),
)

@OptIn(FlowPreview::class)
class MapSessionViewModel(
    savedStateHandle: SavedStateHandle,
    private val mapInteract: MapInteract,
    private val socketHelper: SocketHelper,
) : ViewModel() {

    private val incidentId: String = checkNotNull(savedStateHandle["incidentId"])
    private val campusId: String = checkNotNull(savedStateHandle["campusId"])
    private val initialTicket: String = checkNotNull(savedStateHandle["wsTicket"])

    private val _state = MutableStateFlow(MapSessionUiState())
    val state: StateFlow<MapSessionUiState> = _state.asStateFlow()

    /** High-frequency poses (demo + live) — collected separately so map chrome does not rebuild each frame. */
    private val _drones = MutableStateFlow<Map<String, DroneMapPose>>(emptyMap())
    val drones: StateFlow<Map<String, DroneMapPose>> = _drones.asStateFlow()

    private val _overlays = MutableStateFlow(MapOverlays())
    val overlays: StateFlow<MapOverlays> = _overlays.asStateFlow()

    private val _focusDrone = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Emitted when a drone should be scrolled into view and expanded (e.g. map tap). */
    val focusDrone: SharedFlow<String> = _focusDrone.asSharedFlow()

    private var mapConfig: MapConfigResponseDto? = null
    private var allAnnotations: List<MosaicAnnotationDto> = emptyList()
    private var projector: GeoProjector? = null
    private var transform: FloorPlanTransform? = null
    private var previousBuildingId: String? = null
    private var previousFloorId: String? = null
    private val firstConnect = AtomicBoolean(true)
    private var poseLogCounter = 0
    /** Latest raw telemetry — projected/filtered for the active outdoor/floor layer in [publishDrones]. */
    private var liveTelemetry: Map<String, DroneTelemetry> = emptyMap()
    private var demoTelemetry: DroneTelemetry? = null
    private var demoWaypoints: List<DemoWaypoint> = emptyList()
    private var demoWaypointIndex = 0
    private var demoWaypointStartedAtMs = 0L
    private var demoAngleDeg = 0.0
    private var demoLastFrameMs = 0L
    private var lastFollowedPlacementKey: String? = null
    private var activeThreats: List<MapThreat> = emptyList()
    private var colorPathways: List<ColorPathway> = emptyList()
    private var threatBannerJob: Job? = null
    private var focusNonce = 0L

    init {
        AppLog.i(
            TAG,
            "MapSession init incidentId=$incidentId campusId=$campusId " +
                "ticket=${AppLog.redact(initialTicket)}",
        )
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch { runDemoOrbit() }
        viewModelScope.launch {
            socketHelper.connected.collect { connected ->
                AppLog.d(TAG, "socket connected=$connected")
                _state.update {
                    it.copy(
                        socketConnected = connected,
                        statusMessage = if (connected) "Live" else "Reconnecting…",
                    )
                }
            }
        }
        viewModelScope.launch {
            socketHelper.events.collect { event ->
                AppLog.d(TAG, "socket event=$event")
                when (event) {
                    is SocketEvent.Connected ->
                        _state.update { it.copy(statusMessage = "Live", socketConnected = true) }
                    is SocketEvent.Reconnecting, is SocketEvent.Disconnected ->
                        _state.update { it.copy(statusMessage = "Reconnecting…", socketConnected = false) }
                    is SocketEvent.Failed ->
                        _state.update { it.copy(statusMessage = "Reconnecting… (${event.reason})") }
                    is SocketEvent.ThreatIdentified -> showThreatBanner(event.threatId)
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            socketHelper.drones.sample(AppConfig.TELEMETRY_UI_SAMPLE_MS).collect { drones ->
                liveTelemetry = drones
                poseLogCounter++
                if (poseLogCounter % 30 == 1) {
                    AppLog.d(TAG, "Telemetry sample count=${drones.size} ids=${drones.keys}")
                }
                publishDrones()
                followSelectedDroneIfNeeded()
            }
        }
        viewModelScope.launch {
            socketHelper.threats.collect { threats ->
                activeThreats = threats.values.map { it.toMapThreat(mapConfig, campusId) }
                AppLog.d(TAG, "threats=${activeThreats.map { "${it.id}@${it.buildingId}/${it.floorId}" }}")
                val banner = _state.value.threatBanner
                if (banner != null && activeThreats.none { it.id == banner.threatId }) {
                    dismissThreatBanner()
                }
                publishOverlays()
            }
        }
        viewModelScope.launch {
            socketHelper.colorPathways.collect { pathways ->
                colorPathways = pathways.values.mapNotNull { it.toColorPathway(mapConfig, campusId) }
                AppLog.d(TAG, "colorPathways=${colorPathways.map { "${it.id}:${it.points.size}pts" }}")
                publishOverlays()
            }
        }
    }

    private suspend fun bootstrap() {
        AppLog.i(TAG, "bootstrap map + annotations")
        _state.update { it.copy(loading = true, error = null, mapUnavailable = false) }
        runCatching {
            val config = mapInteract.loadMapConfig(campusId)
            mapConfig = config
            val campusName = config.campusName.ifBlank { "Campus" }

            // Same rule as Mithril ResponseStateMap.isMapAvailable.
            val outdoor = config.outdoor
            if (outdoor == null && config.buildings.isEmpty()) {
                AppLog.w(TAG, "Map unavailable — no outdoor or buildings for campus=$campusId")
                _state.update {
                    it.copy(
                        loading = false,
                        mapUnavailable = true,
                        campusName = campusName,
                        showBuildingPicker = false,
                        error = null,
                    )
                }
                return
            }

            val annotations = runCatching {
                mapInteract.loadAnnotations(campusId).map { ann ->
                    ann.normalizedForMapConfig(config, campusId)
                }
            }.getOrElse { e ->
                AppLog.w(TAG, "annotations load failed — continuing without markers", e)
                emptyList()
            }
            allAnnotations = annotations

            val outdoorXf = outdoor?.let { outdoorTransform(it) }
            val latLng = config.campusLatLng()
            AppLog.d(
                TAG,
                "campusLatLng=$latLng outdoor=${outdoor?.cols}x${outdoor?.rows} " +
                    "buildings=${config.buildings.size} annotations=${annotations.size}",
            )
            val geo = createCampusGeoProjector(
                campusLat = latLng?.first,
                campusLng = latLng?.second,
                annotations = annotations,
                outdoorTransform = outdoorXf,
            ) ?: throw IllegalStateException("Unable to calibrate campus geo projector")

            projector = geo
            AppLog.i(TAG, "Geo projector calibrated=${geo.isCalibrated}")

            val buildings = buildingPickerItems(config).map { (id, name) ->
                val floorCount = if (id == OUTDOOR_BUILDING_ID) {
                    1
                } else {
                    findBuilding(config, id)?.floors?.size ?: 0
                }
                BuildingListItem(id = id, name = name, floorCount = floorCount)
            }

            _state.update {
                it.copy(
                    loading = false,
                    campusName = campusName,
                    buildings = buildings,
                    mapUnavailable = false,
                    error = null,
                )
            }

            demoWaypoints = buildDemoItinerary(config)
            demoWaypointIndex = 0
            demoWaypointStartedAtMs = 0L
            AppLog.i(TAG, "Demo itinerary waypoints=${demoWaypoints.size}: $demoWaypoints")

            when {
                outdoor != null -> switchMapState(OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID)
                else -> {
                    val firstBuilding = config.buildings.firstOrNull()
                    val firstFloor = firstBuilding?.floors?.firstOrNull()
                    if (firstBuilding != null && firstFloor != null) {
                        switchMapState(firstBuilding.id, firstFloor.floorId)
                    } else {
                        _state.update { it.copy(showBuildingPicker = true) }
                    }
                }
            }
            connectSocket()
        }.onFailure { e ->
            if (isMapNotFound(e)) {
                AppLog.w(TAG, "Map unavailable for campus=$campusId — ${e.message}")
                _state.update {
                    it.copy(
                        loading = false,
                        mapUnavailable = true,
                        showBuildingPicker = false,
                        error = null,
                        campusName = it.campusName.ifBlank { "Campus" },
                    )
                }
                return@onFailure
            }
            AppLog.e(TAG, "bootstrap failed", e)
            _state.update {
                it.copy(loading = false, error = e.message ?: "Failed to load map", mapUnavailable = false)
            }
        }
    }

    private fun isMapNotFound(error: Throwable): Boolean {
        val http = error as? HttpException ?: error.cause as? HttpException
        if (http?.code() == 404) return true
        val message = error.message?.lowercase().orEmpty()
        return message.contains("not found") ||
            message.contains("no map") ||
            message.contains("map unavailable")
    }

    fun selectBuilding(buildingId: String) {
        val bid = normalizeBuildingId(buildingId)
        val floorId = if (bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true)) {
            OUTDOOR_FLOOR_ID
        } else {
            findBuilding(mapConfig, bid)?.floors?.firstOrNull()?.floorId
                ?: OUTDOOR_FLOOR_ID
        }
        AppLog.i(TAG, "selectBuilding id=$bid → floor=$floorId")
        switchMapState(bid, floorId)
    }

    fun selectFloor(floorId: String) {
        val bid = _state.value.selectedBuildingId ?: return
        AppLog.i(TAG, "selectFloor building=$bid floor=$floorId")
        switchMapState(bid, floorId)
    }

    fun openBuildingPicker() {
        val current = _state.value.selectedBuildingId
        if (current != null) {
            previousBuildingId = current
            previousFloorId = _state.value.selectedFloorId
        }
        AppLog.i(TAG, "openBuildingPicker previous=$previousBuildingId")
        _state.update {
            it.copy(
                showBuildingPicker = true,
                selectedBuildingId = null,
            )
        }
    }

    private fun switchMapState(buildingId: String, floorId: String) {
        val config = mapConfig ?: return
        val bid = normalizeBuildingId(buildingId)
        val layer = findMapLayer(config, bid, floorId)
        if (layer == null) {
            AppLog.e(TAG, "No mosaic layer for building=$bid floor=$floorId")
            _state.update { it.copy(error = "Map layer unavailable") }
            return
        }

        val xf = layer.toTransform()
        transform = xf
        previousBuildingId = bid
        previousFloorId = floorId

        val buildingName = when {
            bid.equals(OUTDOOR_BUILDING_ID, ignoreCase = true) -> "Outdoor Map"
            else -> findBuilding(config, bid)?.name?.ifBlank { "Building" } ?: "Building"
        }
        val floors = floorsForBuilding(config, bid).map { (id, label) ->
            FloorListItem(id = id, label = label)
        }
        val resolvedFloorId = floors.find {
            it.id.equals(floorId, ignoreCase = true) || it.label.equals(floorId, ignoreCase = true)
        }?.id ?: floors.firstOrNull()?.id ?: floorId
        val floorLabel = floors.find { it.id == resolvedFloorId }?.label ?: resolvedFloorId

        val mosaic = MosaicLayerConfig(
            cols = layer.layerCols().coerceAtLeast(1),
            rows = layer.layerRows().coerceAtLeast(1),
            tileWidth = layer.layerTileWidth(),
            tileHeight = layer.layerTileHeight(),
            tiles = layer.layerTiles(),
            overviewUrl = layer.layerOverviewUrl(),
            layerKey = "${bid}_$resolvedFloorId",
        )

        AppLog.i(
            TAG,
            "switchMapState building=$bid floor=$resolvedFloorId " +
                "tiles=${mosaic.tiles.size} overview=${mosaic.overviewUrl != null}",
        )

        _state.update {
            it.copy(
                showBuildingPicker = false,
                selectedBuildingId = bid,
                selectedBuildingName = buildingName,
                selectedFloorId = resolvedFloorId,
                selectedFloorLabel = floorLabel,
                floors = floors,
                mosaic = mosaic,
                error = null,
            )
        }
        publishDrones()
        publishOverlays()
    }

    /** Pathways draw on every layer (Mithril floor guard disabled); threats filter by floor. */
    private fun publishOverlays() {
        val xf = transform
        if (xf == null) {
            _overlays.value = MapOverlays()
            return
        }
        val ui = _state.value
        val pathways = colorPathways
            .filter { it.isVisibleOnLayer() }
            .map { it.toRender(xf) }
        val threats = activeThreats
            .filter { it.isVisibleOnLayer(mapConfig, ui.selectedBuildingId, ui.selectedFloorId) }
            .map { threat ->
                val pixel = unrealToFloorPlanPixel(PointF(threat.unrealX, threat.unrealY), xf)
                ThreatMarker(id = threat.id, label = threat.name, pixelX = pixel.x, pixelY = pixel.y)
            }
        _overlays.value = MapOverlays(pathways = pathways, threats = threats)
    }

    private fun showThreatBanner(threatId: String) {
        val count = socketHelper.threats.value.size
        AppLog.i(TAG, "Threat identified id=$threatId count=$count")
        _state.update { it.copy(threatBanner = ThreatBannerUi(threatCount = count, threatId = threatId)) }
        threatBannerJob?.cancel()
        threatBannerJob = viewModelScope.launch {
            delay(THREAT_BANNER_DURATION_MS)
            _state.update { it.copy(threatBanner = null) }
        }
    }

    /** Clears the one-shot focus so it is not replayed after rotation / re-collection. */
    fun onMapFocusApplied(nonce: Long) {
        _state.update { if (it.mapFocus?.nonce == nonce) it.copy(mapFocus = null) else it }
    }

    fun dismissThreatBanner() {
        threatBannerJob?.cancel()
        threatBannerJob = null
        _state.update { it.copy(threatBanner = null) }
    }

    /**
     * Mithril performLocateThreat: jump to the threat's outdoor/building floor and center on it.
     * Drops drone follow first, otherwise the next telemetry tick would switch layers back.
     */
    fun locateThreat(threatId: String? = _state.value.threatBanner?.threatId) {
        val threat = activeThreats.find { it.id == threatId } ?: activeThreats.lastOrNull() ?: return
        AppLog.i(TAG, "locateThreat id=${threat.id} → ${threat.buildingId} | ${threat.floorId}")
        lastFollowedPlacementKey = null
        _state.update { it.copy(selectedDroneId = null) }
        switchMapState(threat.buildingId, threat.floorId)
        val xf = transform ?: return
        val layerKey = _state.value.mosaic?.layerKey ?: return
        val pixel = unrealToFloorPlanPixel(PointF(threat.unrealX, threat.unrealY), xf)
        _state.update {
            it.copy(mapFocus = MapFocus(layerKey, pixel.x, pixel.y, nonce = ++focusNonce))
        }
    }

    /**
     * Circular demo path synced to display frames (~60 Hz). Dwelling a few seconds on
     * outdoor, then each building floor in turn so floor-follow can be exercised.
     */
    private suspend fun runDemoOrbit() {
        demoLastFrameMs = 0L
        while (true) {
            awaitNextFrame()
            val config = mapConfig ?: continue
            val waypoints = demoWaypoints
            if (waypoints.isEmpty()) continue

            val now = SystemClock.uptimeMillis()
            val dtSec = if (demoLastFrameMs == 0L) {
                1.0 / 60.0
            } else {
                ((now - demoLastFrameMs).coerceIn(1L, 50L)) / 1000.0
            }
            demoLastFrameMs = now

            if (demoWaypointStartedAtMs == 0L) {
                demoWaypointStartedAtMs = now
            } else if (now - demoWaypointStartedAtMs >= DEMO_FLOOR_DWELL_MS) {
                demoWaypointIndex = (demoWaypointIndex + 1) % waypoints.size
                demoWaypointStartedAtMs = now
                AppLog.i(TAG, "Demo drone → ${waypoints[demoWaypointIndex]}")
            }

            val wp = waypoints[demoWaypointIndex]
            val layer = findMapLayer(config, wp.buildingId, wp.floorId) ?: continue
            val layerXf = layer.toTransform()
            val cx = layerXf.worldWidth * 0.42f
            val cy = layerXf.worldHeight * 0.38f
            val radius = min(layerXf.worldWidth, layerXf.worldHeight) * 0.12f
            demoAngleDeg = (demoAngleDeg + DEMO_ORBIT_DEG_PER_SEC * dtSec) % 360.0
            val rad = Math.toRadians(demoAngleDeg)
            val pixel = PointF(
                cx + (radius * cos(rad)).toFloat(),
                cy + (radius * sin(rad)).toFloat(),
            )
            val unreal = unrealFromFloorPlanPixel(pixel, layerXf)
            val heading = ((demoAngleDeg + 90.0) % 360.0)
            // Convert yaw degrees → quaternion (Z-up) so [DroneTelemetry.headingDegrees] matches.
            val halfYaw = Math.toRadians(heading) / 2.0
            demoTelemetry = DroneTelemetry(
                displayId = DEMO_DRONE_ID,
                deviceId = DEMO_DRONE_ID,
                droneIp = "127.0.0.1",
                state = "ACTIVE",
                batteryPercent = 87,
                deviceType = "DEMO",
                buildingId = wp.buildingId,
                floorId = wp.floorId,
                positionX = unreal.x.toDouble(),
                positionY = unreal.y.toDouble(),
                positionZ = wp.altitudeMeters,
                rotationX = 0.0,
                rotationY = 0.0,
                rotationZ = sin(halfYaw),
                rotationW = cos(halfYaw),
                confidence = 0.92,
                capturedAt = System.currentTimeMillis(),
                frameUrl = null,
                videoDeviceStatus = "OK",
                micDeviceStatus = "OK",
                audioDeviceStatus = "OK",
                halowConnectivityStatus = "OK",
                gyroStatus = "OK",
                accStatus = "OK",
                barometerStatus = "OK",
                escStatus = "OK",
                batteryUnitStatus = "OK",
                motorStatus = "OK",
            )
            publishDrones()
            followSelectedDroneIfNeeded()
        }
    }

    private fun buildDemoItinerary(config: MapConfigResponseDto): List<DemoWaypoint> {
        val waypoints = mutableListOf<DemoWaypoint>()
        if (config.outdoor != null) {
            waypoints += DemoWaypoint(OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID, altitudeMeters = 1.8)
        }
        val building = config.buildings.firstOrNull { it.floors.isNotEmpty() }
        if (building != null) {
            val bid = building.id.ifBlank { building.buildingId.orEmpty() }
            if (bid.isNotBlank()) {
                building.floors.forEachIndexed { index, floor ->
                    val floorId = floor.floorId.ifBlank { floor.floorLabel }.ifBlank { "floor-$index" }
                    val level = floor.level ?: index
                    waypoints += DemoWaypoint(
                        buildingId = bid,
                        floorId = floorId,
                        altitudeMeters = level * METERS_PER_FLOOR + 1.8,
                    )
                }
            }
        }
        return waypoints.ifEmpty {
            listOf(DemoWaypoint(OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID, altitudeMeters = 1.8))
        }
    }

    /**
     * Outdoor: every live/demo drone. Indoor: only drones on that building floor.
     * Reprojects Unreal → pixels for the active transform.
     */
    private fun publishDrones() {
        val xf = transform
        val ui = _state.value
        val config = mapConfig
        val allTelemetry = buildMap {
            putAll(liveTelemetry)
            demoTelemetry?.let { put(DEMO_DRONE_ID, it) }
        }
        val visible = if (xf == null) {
            emptyMap()
        } else {
            allTelemetry.mapNotNull { (id, telemetry) ->
                if (!shouldShowDroneOnLayer(
                        telemetry = telemetry,
                        config = config,
                        campusId = campusId,
                        selectedBuildingId = ui.selectedBuildingId,
                        selectedFloorId = ui.selectedFloorId,
                    )
                ) {
                    return@mapNotNull null
                }
                val pixel = unrealToFloorPlanPixel(
                    PointF(telemetry.positionX.toFloat(), telemetry.positionY.toFloat()),
                    xf,
                )
                id to telemetry.toMapPose(pixel.x, pixel.y)
            }.toMap()
        }
        ensureDroneSelectionTracked(allTelemetry.keys)
        if (poseLogCounter % 30 == 1) {
            AppLog.d(
                TAG,
                "Map poses building=${ui.selectedBuildingId} floor=${ui.selectedFloorId} " +
                    "visible=${visible.size}/${allTelemetry.size} ids=${visible.keys}",
            )
        }
        _drones.value = visible
    }

    /** Drop expansion only if the drone left telemetry entirely (not just off this floor). */
    private fun ensureDroneSelectionTracked(trackedIds: Set<String>) {
        val current = _state.value.selectedDroneId ?: return
        if (current !in trackedIds) {
            lastFollowedPlacementKey = null
            _state.update { it.copy(selectedDroneId = null) }
        }
    }

    /**
     * Mithril threat follow: while a drone is selected, auto-switch outdoor/building/floor
     * whenever its placement changes so it stays visible.
     */
    private fun followSelectedDroneIfNeeded() {
        val selectedId = _state.value.selectedDroneId ?: run {
            lastFollowedPlacementKey = null
            return
        }
        if (_state.value.showBuildingPicker) return
        val telemetry = when (selectedId) {
            DEMO_DRONE_ID -> demoTelemetry
            else -> liveTelemetry[selectedId]
        } ?: return

        val (buildingId, floorId) = resolveDroneMapPlacement(telemetry, mapConfig, campusId)
        val placementKey = "$selectedId|$buildingId|$floorId"
        if (placementKey == lastFollowedPlacementKey &&
            isSameMapLayer(
                buildingId,
                floorId,
                _state.value.selectedBuildingId,
                _state.value.selectedFloorId,
            )
        ) {
            return
        }

        if (!isSameMapLayer(
                buildingId,
                floorId,
                _state.value.selectedBuildingId,
                _state.value.selectedFloorId,
            )
        ) {
            AppLog.i(
                TAG,
                "Followed drone $selectedId moved to $buildingId | $floorId — switching views",
            )
            switchMapState(buildingId, floorId)
        }
        lastFollowedPlacementKey = placementKey
    }

    private fun isSameMapLayer(
        buildingA: String?,
        floorA: String?,
        buildingB: String?,
        floorB: String?,
    ): Boolean {
        val ba = normalizeBuildingId(buildingA)
        val bb = normalizeBuildingId(buildingB)
        if (!ba.equals(bb, ignoreCase = true)) return false
        if (ba.equals(OUTDOOR_BUILDING_ID, ignoreCase = true)) return true
        val a = floorA.orEmpty()
        val b = floorB.orEmpty()
        if (a.equals(b, ignoreCase = true)) return true
        val building = findBuilding(mapConfig, ba) ?: return false
        return floorsReferToSame(building, a, b)
    }

    private suspend fun awaitNextFrame() = suspendCancellableCoroutine { cont ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { cont.resume(Unit) }
        cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        choreographer.postFrameCallback(callback)
    }

    private fun connectSocket() {
        firstConnect.set(true)
        socketHelper.connect {
            val ticket = if (firstConnect.compareAndSet(true, false)) {
                AppLog.d(TAG, "Using initial join ws ticket")
                initialTicket
            } else {
                AppLog.d(TAG, "Refreshing ws ticket for reconnect")
                mapInteract.refreshWsTicket(incidentId)
            }
            SocketHelper.buildWsUrl(AppConfig.apiBaseUrl, ticket, incidentId)
        }
    }

    /**
     * List or map tap:
     * - same drone already expanded → collapse + clear follow
     * - otherwise → expand + follow that drone across floors/buildings (Mithril threat follow)
     */
    fun toggleDroneExpanded(id: String, fromMap: Boolean = false) {
        val collapsing = _state.value.selectedDroneId == id
        AppLog.d(TAG, "toggleDroneExpanded=$id collapsing=$collapsing fromMap=$fromMap")
        if (collapsing) {
            lastFollowedPlacementKey = null
            _state.update { it.copy(selectedDroneId = null) }
            return
        }
        lastFollowedPlacementKey = null
        _state.update { it.copy(selectedDroneId = id) }
        followSelectedDroneIfNeeded()
        if (fromMap) {
            viewModelScope.launch { _focusDrone.emit(id) }
        }
    }

    override fun onCleared() {
        AppLog.i(TAG, "onCleared — disconnect socket")
        socketHelper.disconnect()
        super.onCleared()
    }

    private data class DemoWaypoint(
        val buildingId: String,
        val floorId: String,
        val altitudeMeters: Double,
    )

    companion object {
        private const val TAG = "MapSessionVM"
        const val DEMO_DRONE_ID = "DEMO-01"
        /** Full circle ≈ 12 s — matches a calm telemetry track feel. */
        private const val DEMO_ORBIT_DEG_PER_SEC = 30.0
        /** Seconds on each outdoor/floor stop before advancing the demo itinerary. */
        private const val DEMO_FLOOR_DWELL_MS = 8_000L
        /** Mithril MapViewModel.onThreatAdded hides the overlay after 5 s. */
        private const val THREAT_BANNER_DURATION_MS = 5_000L
    }
}

private fun com.guardianangel.mithrildefense.domain.model.DroneTelemetry.toMapPose(
    pixelX: Float,
    pixelY: Float,
): DroneMapPose = DroneMapPose(
    droneDisplayId = displayId,
    deviceId = deviceId,
    droneIp = droneIp,
    pixelX = pixelX,
    pixelY = pixelY,
    headingDegrees = headingDegrees.toFloat(),
    batteryPercent = batteryPercent,
    state = state,
    positionX = positionX,
    positionY = positionY,
    positionZ = positionZ,
    confidence = confidence,
    capturedAt = capturedAt,
    motorStatus = motorStatus,
    escStatus = escStatus,
    batteryUnitStatus = batteryUnitStatus,
    gyroStatus = gyroStatus,
    accStatus = accStatus,
    barometerStatus = barometerStatus,
    videoDeviceStatus = videoDeviceStatus,
    micDeviceStatus = micDeviceStatus,
    audioDeviceStatus = audioDeviceStatus,
    halowConnectivityStatus = halowConnectivityStatus,
)
