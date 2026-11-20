package com.guardianangel.dronetelementry.presentation.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.dronetelementry.config.AppConfig
import com.guardianangel.dronetelementry.data.model.MapConfigResponseDto
import com.guardianangel.dronetelementry.data.model.MapTileDto
import com.guardianangel.dronetelementry.data.model.MosaicAnnotationDto
import com.guardianangel.dronetelementry.data.socket.SocketEvent
import com.guardianangel.dronetelementry.data.socket.SocketHelper
import com.guardianangel.dronetelementry.domain.interact.MapInteract
import com.guardianangel.dronetelementry.domain.model.CurrentPilot
import com.guardianangel.dronetelementry.domain.model.DroneMapPose
import com.guardianangel.dronetelementry.domain.model.DroneTelemetry
import com.guardianangel.dronetelementry.map.ColorPathway
import com.guardianangel.dronetelementry.map.FloorPlanTransform
import com.guardianangel.dronetelementry.map.GeoProjector
import com.guardianangel.dronetelementry.map.METERS_PER_FLOOR
import com.guardianangel.dronetelementry.data.model.CampusDroneDto
import com.guardianangel.dronetelementry.data.remote.IncidentEndedNotice
import com.guardianangel.dronetelementry.map.MapThreat
import com.guardianangel.dronetelementry.map.OUTDOOR_BUILDING_ID
import com.guardianangel.dronetelementry.map.OUTDOOR_FLOOR_ID
import com.guardianangel.dronetelementry.map.PathwayRender
import com.guardianangel.dronetelementry.map.PointF
import com.guardianangel.dronetelementry.map.ThreatMarker
import com.guardianangel.dronetelementry.map.buildingPickerItems
import com.guardianangel.dronetelementry.map.campusLatLng
import com.guardianangel.dronetelementry.map.createCampusGeoProjector
import com.guardianangel.dronetelementry.map.findBuilding
import com.guardianangel.dronetelementry.map.findMapLayer
import com.guardianangel.dronetelementry.map.floorTransform
import com.guardianangel.dronetelementry.map.floorsForBuilding
import com.guardianangel.dronetelementry.map.floorsReferToSame
import com.guardianangel.dronetelementry.map.isVisibleOnLayer
import com.guardianangel.dronetelementry.map.layerCols
import com.guardianangel.dronetelementry.map.layerOverviewUrl
import com.guardianangel.dronetelementry.map.layerRows
import com.guardianangel.dronetelementry.map.layerTileHeight
import com.guardianangel.dronetelementry.map.layerTileWidth
import com.guardianangel.dronetelementry.map.layerTiles
import com.guardianangel.dronetelementry.map.normalizeBuildingId
import com.guardianangel.dronetelementry.map.normalizedForMapConfig
import com.guardianangel.dronetelementry.map.outdoorTransform
import com.guardianangel.dronetelementry.map.resolveDroneMapPlacement
import com.guardianangel.dronetelementry.map.shouldShowDroneOnLayer
import com.guardianangel.dronetelementry.map.segmentsForLayer
import com.guardianangel.dronetelementry.map.toColorPathway
import com.guardianangel.dronetelementry.map.toRenders
import com.guardianangel.dronetelementry.map.toMapThreat
import com.guardianangel.dronetelementry.map.toTransform
import com.guardianangel.dronetelementry.map.unrealFootprintFromTransform
import com.guardianangel.dronetelementry.map.unrealFromFloorPlanPixel
import com.guardianangel.dronetelementry.map.unrealToFloorPlanPixel
import com.guardianangel.dronetelementry.utils.AppLog
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    /** Mithril MapBuildingPicker BuildingStatus.CRITICAL — an active threat is in this building. */
    val hasThreat: Boolean = false,
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
    /**
     * Marker the 2D map camera keeps centred (Mithril followedThreatId). The map flies to it when
     * [CameraFollow.nonce] changes, then re-centres in the same frame each time the marker moves.
     */
    val cameraFollow: CameraFollow? = null,
    /** Active threats — badge on the Mithril locate-threat button (button disabled at 0). */
    val threatCount: Int = 0,
    /** Google-Maps-style tilted, heading-up camera. */
    val navigationView: Boolean = false,
    /** Settings → Map zoom level: zoom each layer opens at (multiple of fit). */
    val defaultZoom: Float = MapInteract.DEFAULT_MAP_ZOOM,
) {
    val followedThreatId: String?
        get() = cameraFollow?.takeIf { it.kind == CameraFollow.Kind.THREAT }?.id
}

/** Row in the Mithril ThreatSelectionDialog: name + "Building | Floor". */
data class ThreatListItem(
    val id: String,
    val name: String,
    val location: String,
)

data class ThreatBannerUi(
    val threatCount: Int,
    val threatId: String,
)

data class CameraFollow(
    val kind: Kind,
    val id: String,
    /** Bumped on every user request so locating the same marker again re-runs the fly-to. */
    val nonce: Long,
) {
    enum class Kind { THREAT, DRONE }
}

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

    private val _droneList = MutableStateFlow<List<DroneMapPose>>(emptyList())
    /**
     * Side-panel rows: every campus drone from `campuses/drones/` (API), with live telemetry
     * merged in when the drone is reporting, plus any telemetry-only drone. Not layer-filtered.
     */
    val droneList: StateFlow<List<DroneMapPose>> = _droneList.asStateFlow()

    private val _overlays = MutableStateFlow(MapOverlays())
    val overlays: StateFlow<MapOverlays> = _overlays.asStateFlow()

    private val _droneRoster = MutableStateFlow<Map<String, CampusDroneDto>>(emptyMap())
    /** Campus drones by display_id — type, roster status, weapons, stream URL. */
    val droneRoster: StateFlow<Map<String, CampusDroneDto>> = _droneRoster.asStateFlow()

    private val _incidentEnded = MutableSharedFlow<IncidentEndedNotice>(replay = 1)
    /** One-shot: the incident closed while this screen was open — navigate Home. */
    val incidentEnded: SharedFlow<IncidentEndedNotice> = _incidentEnded.asSharedFlow()

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
    /** In-memory demo fleet — distinct paths, floors, and status. */
    private val demoDrones: List<DemoDroneActor> =
        if (DEMO_FLEET_ENABLED) DEMO_FLEET.map { DemoDroneActor(profile = it) } else emptyList()
    /** Outdoor + floor stops used by the touring demo drone. */
    private var demoTourWaypoints: List<DemoWaypoint> = emptyList()
    private var demoFloorHomes: List<DemoWaypoint> = emptyList()
    private var demoOutdoorHome: DemoWaypoint =
        DemoWaypoint(OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID, altitudeMeters = 1.8)
    /** Pixel rect on the path mosaic where demo drones are allowed to fly. */
    private var demoPlayArea: DemoPlayArea? = null
    private var demoLastFrameMs = 0L
    private var lastFollowedPlacementKey: String? = null
    private var activeThreats: List<MapThreat> = emptyList()
    private var colorPathways: List<ColorPathway> = emptyList()
    private var threatBannerJob: Job? = null
    private var followNonce = 0L
    /** Signed-in user — matched against drone `pilot_email` (then `pilot_id`) for "Mine". */
    private var currentPilot: CurrentPilot = CurrentPilot()
    /** The pilot's own drone is auto-focused once per session; afterwards the user is in control. */
    private var pilotAutoFocusDone = false

    init {
        AppLog.i(
            TAG,
            "MapSession init incidentId=$incidentId campusId=$campusId " +
                "ticket=${AppLog.redact(initialTicket)}",
        )
        viewModelScope.launch { bootstrap() }
        if (DEMO_FLEET_ENABLED) viewModelScope.launch { runDemoOrbit() }
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
                    is SocketEvent.IncidentEnded -> onIncidentEnded(event.status)
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
                autoFocusPilotDrone()
                followSelectedDroneIfNeeded()
            }
        }
        viewModelScope.launch {
            socketHelper.threats.collect { threats ->
                activeThreats = threats.values.map { it.toMapThreat(mapConfig, campusId) }
                AppLog.d(
                    TAG,
                    "threats=" + threats.values.zip(activeThreats).joinToString { (raw, t) ->
                        "${t.name}(${t.id}) raw=${raw.building?.firstOrNull()}/${raw.floors?.firstOrNull()} " +
                            "→ ${t.buildingId}/${t.floorId}"
                    },
                )
                val banner = _state.value.threatBanner
                if (banner != null && activeThreats.none { it.id == banner.threatId }) {
                    dismissThreatBanner()
                }
                _state.update {
                    it.copy(threatCount = activeThreats.size, buildings = withThreatFlags(it.buildings))
                }
                followThreatAcrossLayersIfNeeded()
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

            // Saved zoom must be in state before the first layer is configured.
            val zoom = runCatching { mapInteract.mapZoomLevel() }.getOrDefault(MapInteract.DEFAULT_MAP_ZOOM)
            _state.update { it.copy(defaultZoom = zoom) }
            currentPilot = runCatching { mapInteract.currentPilot() }.getOrDefault(CurrentPilot())
            viewModelScope.launch { loadDroneRoster() }

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
                    buildings = withThreatFlags(buildings),
                    mapUnavailable = false,
                    error = null,
                )
            }

            demoOutdoorHome = DemoWaypoint(OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID, altitudeMeters = 1.8)
            demoFloorHomes = buildDemoFloorHomes(config)
            // Tour only when there are 2+ indoor floors so the touring unit does not pile onto
            // the single indoor drone's floor. Otherwise it stays outdoor-only.
            demoTourWaypoints = buildDemoTourItinerary(config, demoFloorHomes)
            val pathXf = demoPathTransform(config)
            demoPlayArea = pathXf?.let { buildDemoPlayArea(config, it) }
            demoDrones.forEach { actor ->
                actor.tourIndex = 0
                actor.tourStartedAtMs = 0L
                actor.angleDeg = actor.profile.startAngleDeg
                actor.fixedHome = resolveFixedHome(actor.profile.placement)
            }
            AppLog.i(
                TAG,
                "Demo homes=" + demoDrones.joinToString { actor ->
                    val home = actor.fixedHome
                        ?: demoTourWaypoints.getOrNull(0)
                        ?: demoOutdoorHome
                    "${actor.profile.id}->${home.buildingId}/${home.floorId}"
                } + " floors=${demoFloorHomes.size} tourStops=${demoTourWaypoints.size} " +
                    "playArea=$demoPlayArea",
            )

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
        releaseFocusForManualLayerChange()
        switchMapState(bid, floorId)
    }

    fun selectFloor(floorId: String) {
        val bid = _state.value.selectedBuildingId ?: return
        AppLog.i(TAG, "selectFloor building=$bid floor=$floorId")
        releaseFocusForManualLayerChange()
        switchMapState(bid, floorId)
    }

    /**
     * User picked a building / floor themselves: drop any threat or drone focus, otherwise the
     * follow logic would immediately switch the map back to where the focused item is.
     * Also stops the one-time pilot-drone auto-focus from taking over later.
     */
    private fun releaseFocusForManualLayerChange() {
        val droneId = _state.value.selectedDroneId
        if (droneId != null) AppLog.i(TAG, "Manual layer change — releasing focus on drone $droneId")
        pilotAutoFocusDone = true
        lastFollowedPlacementKey = null
        _state.update { it.copy(selectedDroneId = null) }
        clearCameraFollow()
    }

    fun openBuildingPicker() {
        val current = _state.value.selectedBuildingId
        if (current != null) {
            previousBuildingId = current
            previousFloorId = _state.value.selectedFloorId
        }
        AppLog.i(TAG, "openBuildingPicker previous=$previousBuildingId")
        clearCameraFollow(CameraFollow.Kind.THREAT)
        _state.update {
            it.copy(
                showBuildingPicker = true,
                selectedBuildingId = null,
            )
        }
    }

    /**
     * Back from the building picker: return to the building / floor that was open before it.
     * Returns false when there is nothing to go back to (picker shown on entry), so the
     * caller can treat back as "leave the map".
     */
    fun closeBuildingPicker(): Boolean {
        if (!_state.value.showBuildingPicker) return false
        val bid = previousBuildingId ?: return false
        AppLog.i(TAG, "closeBuildingPicker → back to $bid | $previousFloorId")
        switchMapState(bid, previousFloorId ?: OUTDOOR_FLOOR_ID)
        return true
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

    /** Pathways draw only the chunks on the selected floor (all on outdoor); threats filter by floor. */
    private fun publishOverlays() {
        val xf = transform
        if (xf == null) {
            _overlays.value = MapOverlays()
            return
        }
        val ui = _state.value
        val pathways = colorPathways.flatMap { pathway ->
            pathway.toRenders(
                xf,
                pathway.segmentsForLayer(mapConfig, ui.selectedBuildingId, ui.selectedFloorId),
            )
        }
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

    /** User panned / zoomed the 2D map: hand the camera back (Mithril onMapInteraction). */
    fun onMapUserInteraction() {
        if (_state.value.cameraFollow == null) return
        AppLog.d(TAG, "Map gesture — camera follow released")
        clearCameraFollow()
    }

    /** Clears the camera follow; with [kind] only when the current follow is of that kind. */
    private fun clearCameraFollow(kind: CameraFollow.Kind? = null, id: String? = null) {
        _state.update { ui ->
            val follow = ui.cameraFollow ?: return@update ui
            val matches = (kind == null || follow.kind == kind) && (id == null || follow.id == id)
            if (matches) ui.copy(cameraFollow = null) else ui
        }
    }

    /**
     * Mithril followed-threat auto-navigation: when the followed threat moves to another
     * outdoor/building/floor layer, switch views so the camera can keep it centred. Moves within
     * the layer need nothing here — the map view re-centres in the frame the marker moves.
     */
    private fun followThreatAcrossLayersIfNeeded() {
        val follow = _state.value.cameraFollow
        if (follow?.kind != CameraFollow.Kind.THREAT) return
        val threat = activeThreats.find { it.id == follow.id }
        if (threat == null) {
            AppLog.i(TAG, "Followed threat ${follow.id} gone — camera follow cleared")
            clearCameraFollow(CameraFollow.Kind.THREAT, follow.id)
            return
        }
        val ui = _state.value
        if (ui.showBuildingPicker) return
        if (!isSameMapLayer(threat.buildingId, threat.floorId, ui.selectedBuildingId, ui.selectedFloorId)) {
            AppLog.i(TAG, "Followed threat ${threat.id} moved to ${threat.buildingId} | ${threat.floorId} — switching views")
            switchMapState(threat.buildingId, threat.floorId)
        }
    }

    fun dismissThreatBanner() {
        threatBannerJob?.cancel()
        threatBannerJob = null
        _state.update { it.copy(threatBanner = null) }
    }

    /**
     * Mithril performLocateThreat: jump to the threat's outdoor/building floor, fly to it and keep
     * following it. Drops drone follow first, otherwise the next telemetry tick would switch
     * layers back.
     */
    /**
     * Mithril ThreatSelectionDialog rows, natural-sorted by name ("Threat_2" before "Threat_10").
     */
    fun threatListItems(): List<ThreatListItem> {
        val config = mapConfig
        return activeThreats
            .sortedWith(
                compareBy<MapThreat> { it.name.replace(TRAILING_NUMBER, "") }
                    .thenBy { TRAILING_NUMBER.find(it.name)?.value?.toLongOrNull() ?: 0L },
            )
            .map { threat ->
                val location = if (threat.buildingId.equals(OUTDOOR_BUILDING_ID, ignoreCase = true)) {
                    "Outdoor"
                } else {
                    val building = findBuilding(config, threat.buildingId)
                    val buildingName = building?.name?.ifBlank { null } ?: threat.buildingId
                    val floorLabel = building?.floors
                        ?.find { it.floorId.equals(threat.floorId, ignoreCase = true) }
                        ?.floorLabel?.ifBlank { null }
                        ?: threat.floorId
                    "$buildingName | $floorLabel"
                }
                ThreatListItem(id = threat.id, name = threat.name, location = location)
            }
    }

    /**
     * Mithril locate-threat button: one threat → focus it; picking the already-followed threat
     * from the list clears the focus instead.
     */
    fun onThreatSelected(threatId: String) {
        if (_state.value.followedThreatId == threatId) {
            AppLog.i(TAG, "Threat $threatId already focused — clearing focus")
            clearCameraFollow(CameraFollow.Kind.THREAT, threatId)
        } else {
            locateThreat(threatId)
        }
    }

    /** Campus drone roster (`campuses/drones/`, all pages); failures keep the map usable. */
    private suspend fun loadDroneRoster() {
        runCatching { mapInteract.loadCampusDrones(campusId) }
            .onSuccess { drones ->
                _droneRoster.value = drones
                    .filter { it.displayId.isNotBlank() }
                    .associateBy { it.displayId }
                AppLog.i(
                    TAG,
                    "Drone roster: " + drones.joinToString { "${it.displayId}(${it.type}/${it.status} pilot=${it.pilotId})" },
                )
                publishDrones()
                autoFocusPilotDrone()
            }
            .onFailure { AppLog.w(TAG, "Drone roster load failed — list shows telemetry only", it) }
    }

    /**
     * Default focus: once the map layer is up, select + follow the drone whose `pilot_email` (or,
     * without one, `pilot_id`) is the logged-in user (roster or telemetry). Waits until that drone is reporting telemetry, and
     * never overrides a drone the user already picked.
     */
    private fun autoFocusPilotDrone() {
        if (pilotAutoFocusDone) return
        val pilot = currentPilot.takeIf { it.isKnown } ?: return
        if (_state.value.mosaic == null) return
        if (_state.value.selectedDroneId != null) {
            pilotAutoFocusDone = true
            return
        }
        val demoTelemetry = demoDrones.mapNotNull { it.telemetry }
        val tracked = liveTelemetry.keys + demoTelemetry.map { it.displayId }
        val candidates = linkedSetOf<String>().apply {
            liveTelemetry.values.filter { pilot.owns(it.pilotEmail, it.pilotId) }.mapTo(this) { it.displayId }
            demoTelemetry.filter { pilot.owns(it.pilotEmail, it.pilotId) }.mapTo(this) { it.displayId }
            _droneRoster.value.values.filter { pilot.owns(null, it.pilotId) }.mapTo(this) { it.displayId }
        }
        val droneId = candidates.firstOrNull { it in tracked } ?: return
        pilotAutoFocusDone = true
        AppLog.i(TAG, "Auto-focusing pilot drone $droneId (pilot=${AppLog.redact(pilot.email ?: pilot.userId)})")
        toggleDroneExpanded(droneId, fromMap = true)
    }

    /** Mithril MapBuildingPicker: flag outdoor/buildings that hold an active threat. */
    private fun withThreatFlags(items: List<BuildingListItem>): List<BuildingListItem> =
        items.map { item ->
            val hasThreat = activeThreats.any { threat ->
                threat.buildingId.equals(item.id, ignoreCase = true) ||
                    threat.buildingId.equals(item.name, ignoreCase = true)
            }
            if (item.hasThreat == hasThreat) item else item.copy(hasThreat = hasThreat)
        }

    fun toggleNavigationView() {
        val enabled = !_state.value.navigationView
        AppLog.i(TAG, "navigationView=$enabled")
        _state.update { it.copy(navigationView = enabled) }
    }

    /**
     * Mithril applyIncidentLifecycle(RESOLVED/FALSE_ALARM): leave the session and send the user
     * Home, where the "Alarm Ended" dialog explains why.
     */
    private fun onIncidentEnded(status: String) {
        viewModelScope.launch {
            val role = runCatching { mapInteract.sessionIdentity(campusId).second }.getOrNull()
            val notice = IncidentEndedNotice(
                incidentId = incidentId,
                campusName = _state.value.campusName.ifBlank { "Campus" },
                status = status,
                isLawEnforcement = role.equals(LAW_ENFORCEMENT_ROLE, ignoreCase = true),
            )
            AppLog.i(TAG, "Incident ended ($status) — leaving map session")
            socketHelper.disconnect()
            _incidentEnded.emit(notice)
        }
    }

    fun locateThreat(threatId: String? = _state.value.threatBanner?.threatId) {
        val threat = activeThreats.find { it.id == threatId } ?: activeThreats.lastOrNull() ?: return
        AppLog.i(TAG, "locateThreat id=${threat.id} → ${threat.buildingId} | ${threat.floorId}")
        lastFollowedPlacementKey = null
        _state.update {
            it.copy(
                selectedDroneId = null,
                cameraFollow = CameraFollow(CameraFollow.Kind.THREAT, threat.id, ++followNonce),
            )
        }
        switchMapState(threat.buildingId, threat.floorId)
    }

    /**
     * Multi-drone demo fleet (~60 Hz). Paths are always computed in outdoor mosaic
     * space so all four stay visible on the outdoor map; telemetry building/floor
     * differs per role (fixed outdoor, floor 1, floor 2, or touring).
     */
    private suspend fun runDemoOrbit() {
        demoLastFrameMs = 0L
        while (true) {
            awaitNextFrame()
            val config = mapConfig ?: continue
            val pathXf = demoPathTransform(config) ?: continue
            val play = demoPlayArea ?: buildDemoPlayArea(config, pathXf).also { demoPlayArea = it }

            val now = SystemClock.uptimeMillis()
            val dtSec = if (demoLastFrameMs == 0L) {
                1.0 / 60.0
            } else {
                ((now - demoLastFrameMs).coerceIn(1L, 50L)) / 1000.0
            }
            demoLastFrameMs = now

            for (actor in demoDrones) {
                advanceDemoActor(actor, pathXf, play, now, dtSec)
            }
            publishDrones()
            autoFocusPilotDrone()
            followSelectedDroneIfNeeded()
        }
    }

    /** Prefer outdoor mosaic so every demo unit projects onto the campus overview. */
    private fun demoPathTransform(config: MapConfigResponseDto): FloorPlanTransform? {
        findMapLayer(config, OUTDOOR_BUILDING_ID, OUTDOOR_FLOOR_ID)?.let { return it.toTransform() }
        val home = demoFloorHomes.firstOrNull() ?: return null
        return findMapLayer(config, home.buildingId, home.floorId)?.toTransform()
    }

    /**
     * Keep demo flight inside the campus imagery — prefer the union of building
     * footprints (and outdoor annotations) projected onto the path mosaic; otherwise
     * a padded inset of the mosaic so paths never enter the black letterbox.
     */
    private fun buildDemoPlayArea(
        config: MapConfigResponseDto,
        pathXf: FloorPlanTransform,
    ): DemoPlayArea {
        val world = DemoPlayArea(
            minX = pathXf.worldWidth * DEMO_WORLD_INSET_FRAC,
            minY = pathXf.worldHeight * DEMO_WORLD_INSET_FRAC,
            maxX = pathXf.worldWidth * (1f - DEMO_WORLD_INSET_FRAC),
            maxY = pathXf.worldHeight * (1f - DEMO_WORLD_INSET_FRAC),
        )

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var samples = 0

        fun includePixel(px: Float, py: Float) {
            if (px !in 0f..pathXf.worldWidth || py !in 0f..pathXf.worldHeight) return
            minX = min(minX, px)
            minY = min(minY, py)
            maxX = max(maxX, px)
            maxY = max(maxY, py)
            samples++
        }

        config.buildings.forEach { building ->
            val ground = building.floors.minByOrNull { it.level ?: Int.MAX_VALUE }
                ?: building.floors.firstOrNull()
                ?: return@forEach
            val fp = unrealFootprintFromTransform(floorTransform(ground))
            listOf(
                PointF(fp.minX, fp.minY),
                PointF(fp.maxX, fp.minY),
                PointF(fp.minX, fp.maxY),
                PointF(fp.maxX, fp.maxY),
            ).forEach { unreal ->
                val pixel = unrealToFloorPlanPixel(unreal, pathXf)
                includePixel(pixel.x, pixel.y)
            }
        }

        allAnnotations.forEach { ann ->
            val pixel = unrealToFloorPlanPixel(PointF(ann.x, ann.y), pathXf)
            includePixel(pixel.x, pixel.y)
        }

        if (samples < 2) {
            AppLog.i(TAG, "Demo playArea fallback to world inset $world")
            return world
        }

        val width = (maxX - minX).coerceAtLeast(1f)
        val height = (maxY - minY).coerceAtLeast(1f)
        val padX = width * DEMO_FOOTPRINT_PAD_FRAC
        val padY = height * DEMO_FOOTPRINT_PAD_FRAC
        val content = DemoPlayArea(
            minX = (minX - padX).coerceIn(world.minX, world.maxX),
            minY = (minY - padY).coerceIn(world.minY, world.maxY),
            maxX = (maxX + padX).coerceIn(world.minX, world.maxX),
            maxY = (maxY + padY).coerceIn(world.minY, world.maxY),
        )
        // Degenerate / tiny content → fall back to world inset.
        if (content.width < pathXf.worldWidth * 0.08f || content.height < pathXf.worldHeight * 0.08f) {
            AppLog.i(TAG, "Demo playArea content too small ($content) — using world inset")
            return world
        }
        AppLog.i(TAG, "Demo playArea from campus content $content (samples=$samples)")
        return content
    }

    private fun advanceDemoActor(
        actor: DemoDroneActor,
        pathXf: FloorPlanTransform,
        play: DemoPlayArea,
        now: Long,
        dtSec: Double,
    ) {
        val profile = actor.profile
        val wp = resolveDemoPlacement(actor, now)
        actor.angleDeg = (actor.angleDeg + profile.orbitDegPerSec * dtSec) % 360.0

        // Centers are fractions of the play area (campus content), not the full mosaic.
        val cx = play.minX + play.width * profile.centerXFrac
        val cy = play.minY + play.height * profile.centerYFrac
        val size = clampedDemoPathSize(profile.pathStyle, cx, cy, play, profile.radiusFrac)
        val (rawPixel, heading) = demoPathPoint(profile.pathStyle, actor.angleDeg, cx, cy, size)
        val pixel = play.clamp(rawPixel)
        val unreal = unrealFromFloorPlanPixel(pixel, pathXf)
        // Convert yaw degrees → quaternion (Z-up) so [DroneTelemetry.headingDegrees] matches.
        val halfYaw = Math.toRadians(heading) / 2.0
        actor.telemetry = DroneTelemetry(
            displayId = profile.id,
            deviceId = profile.id,
            droneIp = profile.droneIp,
            state = profile.state,
            batteryPercent = profile.batteryPercent,
            deviceType = "DEMO",
            buildingId = wp.buildingId,
            floorId = wp.floorId,
            positionX = unreal.x.toDouble(),
            positionY = unreal.y.toDouble(),
            positionZ = wp.altitudeMeters + profile.altitudeOffsetMeters,
            rotationX = 0.0,
            rotationY = 0.0,
            rotationZ = sin(halfYaw),
            rotationW = cos(halfYaw),
            confidence = profile.confidence,
            capturedAt = System.currentTimeMillis(),
            frameUrl = null,
            videoDeviceStatus = profile.videoDeviceStatus,
            micDeviceStatus = profile.micDeviceStatus,
            audioDeviceStatus = profile.audioDeviceStatus,
            halowConnectivityStatus = profile.halowConnectivityStatus,
            gyroStatus = profile.gyroStatus,
            accStatus = profile.accStatus,
            barometerStatus = profile.barometerStatus,
            escStatus = profile.escStatus,
            batteryUnitStatus = profile.batteryUnitStatus,
            motorStatus = profile.motorStatus,
            pilotId = currentPilot.userId.takeIf { profile.pilotedByCurrentUser },
            pilotEmail = currentPilot.email.takeIf { profile.pilotedByCurrentUser },
        )
    }

    /** Shrink orbit/cross size so every point of the path stays inside [play]. */
    private fun clampedDemoPathSize(
        style: DemoPathStyle,
        cx: Float,
        cy: Float,
        play: DemoPlayArea,
        radiusFrac: Float,
    ): Float {
        val roomX = min(cx - play.minX, play.maxX - cx).coerceAtLeast(1f)
        val roomY = min(cy - play.minY, play.maxY - cy).coerceAtLeast(1f)
        val maxSize = when (style) {
            DemoPathStyle.OVAL_HORIZONTAL -> min(roomX / 1.45f, roomY / 0.55f)
            DemoPathStyle.OVAL_VERTICAL -> min(roomX / 0.55f, roomY / 1.45f)
            DemoPathStyle.CROSS_PLUS -> min(roomX, roomY) / 1.15f
            // Diagonal arms: extent along each axis is arm / √2.
            DemoPathStyle.CROSS_X -> min(roomX, roomY) * SQRT_2 / 1.15f
        }
        val desired = min(play.width, play.height) * radiusFrac
        val floor = min(play.width, play.height) * 0.03f
        return desired.coerceIn(floor, maxSize.coerceAtLeast(floor))
    }

    /**
     * Fixed roles never share a floor: missing floor-N falls back to outdoor (not floor-1).
     * Touring only moves when [demoTourWaypoints] has outdoor + multiple floors.
     */
    private fun resolveFixedHome(placement: DemoPlacement): DemoWaypoint? = when (placement) {
        DemoPlacement.OUTDOOR -> demoOutdoorHome
        DemoPlacement.FLOOR_1 -> demoFloorHomes.getOrNull(0) ?: demoOutdoorHome
        // Do NOT fall back to floor[0] — that stacked DEMO-02/03/04 on one floor.
        DemoPlacement.FLOOR_2 -> demoFloorHomes.getOrNull(1) ?: demoOutdoorHome
        DemoPlacement.TOURING -> null
    }

    private fun resolveDemoPlacement(actor: DemoDroneActor, now: Long): DemoWaypoint {
        actor.fixedHome?.let { return it }
        // Touring
        val tour = demoTourWaypoints
        if (tour.size <= 1) return demoOutdoorHome
        if (actor.tourStartedAtMs == 0L) {
            actor.tourStartedAtMs = now
        } else if (now - actor.tourStartedAtMs >= DEMO_FLOOR_DWELL_MS) {
            actor.tourIndex = (actor.tourIndex + 1) % tour.size
            actor.tourStartedAtMs = now
            AppLog.i(TAG, "Demo ${actor.profile.id} → ${tour[actor.tourIndex]}")
        }
        return tour[actor.tourIndex.coerceIn(0, tour.lastIndex)]
    }

    /**
     * Path styles in outdoor pixel space. Cross styles trace a + or X through the center
     * (out-and-back on each arm) so the two units visibly cut across each other.
     */
    private fun demoPathPoint(
        style: DemoPathStyle,
        angleDeg: Double,
        cx: Float,
        cy: Float,
        size: Float,
    ): Pair<PointF, Double> {
        val t = Math.toRadians(angleDeg)
        return when (style) {
            DemoPathStyle.OVAL_HORIZONTAL -> {
                val rx = size * 1.45f
                val ry = size * 0.55f
                val x = cx + (rx * cos(t)).toFloat()
                val y = cy + (ry * sin(t)).toFloat()
                val heading = Math.toDegrees(atan2(ry * cos(t), -rx * sin(t)))
                PointF(x, y) to ((heading + 360.0) % 360.0)
            }
            DemoPathStyle.OVAL_VERTICAL -> {
                val rx = size * 0.55f
                val ry = size * 1.45f
                val x = cx + (rx * cos(t)).toFloat()
                val y = cy + (ry * sin(t)).toFloat()
                val heading = Math.toDegrees(atan2(ry * cos(t), -rx * sin(t)))
                PointF(x, y) to ((heading + 360.0) % 360.0)
            }
            DemoPathStyle.CROSS_PLUS -> crossArmPoint(
                angleDeg = angleDeg,
                cx = cx,
                cy = cy,
                arm = size * 1.15f,
                diagonal = false,
            )
            DemoPathStyle.CROSS_X -> crossArmPoint(
                angleDeg = angleDeg,
                cx = cx,
                cy = cy,
                arm = size * 1.15f,
                diagonal = true,
            )
        }
    }

    /** Four out-and-back arms (axis-aligned + or diagonal X). */
    private fun crossArmPoint(
        angleDeg: Double,
        cx: Float,
        cy: Float,
        arm: Float,
        diagonal: Boolean,
    ): Pair<PointF, Double> {
        val phase = ((angleDeg % 360.0) + 360.0) % 360.0
        val leg = (phase / 90.0).toInt().coerceIn(0, 3)
        val u = (phase % 90.0) / 90.0
        // 0..0.5 travel out from center, 0.5..1 return.
        val out = if (u <= 0.5) (u * 2.0) else ((1.0 - u) * 2.0)
        val goingOut = u <= 0.5
        val (dx, dy) = if (diagonal) {
            when (leg) {
                0 -> 1.0 to 1.0 // NE
                1 -> -1.0 to 1.0 // NW
                2 -> -1.0 to -1.0 // SW
                else -> 1.0 to -1.0 // SE
            }
        } else {
            when (leg) {
                0 -> 1.0 to 0.0 // E
                1 -> -1.0 to 0.0 // W
                2 -> 0.0 to -1.0 // N (screen up)
                else -> 0.0 to 1.0 // S
            }
        }
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
        val nx = dx / len
        val ny = dy / len
        val x = cx + (arm * out * nx).toFloat()
        val y = cy + (arm * out * ny).toFloat()
        val headingRad = if (goingOut) {
            atan2(ny, nx)
        } else {
            atan2(-ny, -nx)
        }
        val heading = (Math.toDegrees(headingRad) + 360.0) % 360.0
        return PointF(x, y) to heading
    }

    /** Distinct indoor floors across buildings (sorted by level). One demo drone max per entry. */
    private fun buildDemoFloorHomes(config: MapConfigResponseDto): List<DemoWaypoint> {
        val seen = LinkedHashSet<String>()
        val homes = mutableListOf<Pair<Int, DemoWaypoint>>()
        config.buildings.forEach { building ->
            val bid = building.id.ifBlank { building.buildingId.orEmpty() }
            if (bid.isBlank() || building.floors.isEmpty()) return@forEach
            building.floors.forEachIndexed { index, floor ->
                val floorId = floor.floorId.ifBlank { floor.floorLabel }.ifBlank { "floor-$index" }
                val key = "$bid|$floorId"
                if (!seen.add(key)) return@forEachIndexed
                val level = floor.level ?: index
                homes += level to DemoWaypoint(
                    buildingId = bid,
                    floorId = floorId,
                    altitudeMeters = level * METERS_PER_FLOOR + 1.8,
                )
            }
        }
        return homes.sortedBy { it.first }.map { it.second }
    }

    /**
     * Tour stops = outdoor + every indoor floor, but only when there are at least two
     * indoor floors. With a single floor the touring drone would otherwise sit on the
     * same layer as the dedicated floor-1 unit.
     */
    private fun buildDemoTourItinerary(
        config: MapConfigResponseDto,
        floors: List<DemoWaypoint>,
    ): List<DemoWaypoint> {
        if (floors.size < 2) return listOf(demoOutdoorHome)
        val waypoints = mutableListOf<DemoWaypoint>()
        if (config.outdoor != null) {
            waypoints += demoOutdoorHome
        }
        waypoints += floors
        return waypoints.ifEmpty { listOf(demoOutdoorHome) }
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
            demoDrones.forEach { actor ->
                actor.telemetry?.let { put(actor.profile.id, it) }
            }
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
                id to telemetry.toMapPose(pixel.x, pixel.y).withOwnership()
            }.toMap()
        }
        logDronePlacement(xf, visible)
        val roster = _droneRoster.value
        ensureDroneSelectionTracked(allTelemetry.keys + roster.keys)
        _drones.value = visible
        _droneList.value = (roster.keys + allTelemetry.keys).map { id ->
            visible[id]
                ?: allTelemetry[id]?.toMapPose(0f, 0f)?.withOwnership()
                ?: roster.getValue(id).toRosterPose().withOwnership()
        }
    }

    /**
     * "Mine": telemetry pilot_email (else pilot_id), falling back to the campus roster's
     * pilot_id when telemetry carries neither.
     */
    private fun DroneMapPose.withOwnership(): DroneMapPose {
        val pilotId = pilotId?.takeIf { it.isNotBlank() }
            ?: _droneRoster.value[droneDisplayId]?.pilotId
        return copy(isMine = currentPilot.owns(pilotEmail, pilotId))
    }

    private var placementLogCounter = 0

    /**
     * Diagnostic: layer transform + where each drone lands in pixels vs the layer size, and the
     * first threat for comparison. Throttled; filter Logcat by `DroneTel/MapSessionVM` "placement".
     */
    private fun logDronePlacement(xf: FloorPlanTransform?, visible: Map<String, DroneMapPose>) {
        if (xf == null || visible.isEmpty()) return
        if (placementLogCounter++ % 20 != 0) return
        val threat = _overlays.value.threats.firstOrNull()
        AppLog.d(
            TAG,
            "placement layer=${_state.value.selectedBuildingId}/${_state.value.selectedFloorId} " +
                "origin=(${xf.originX},${xf.originY}) u2px=${xf.unrealToPixels} " +
                "world=${xf.worldWidth}x${xf.worldHeight} " +
                "threat=${threat?.let { "${it.label}@px(${it.pixelX},${it.pixelY})" }} drones=" +
                visible.values.joinToString { p ->
                    "${p.droneDisplayId} unreal(${p.positionX.toInt()},${p.positionY.toInt()}) " +
                        "px(${p.pixelX.toInt()},${p.pixelY.toInt()}) yaw=${p.headingDegrees.toInt()}"
                },
        )
    }

    /** Drop expansion only if the drone left telemetry entirely (not just off this floor). */
    private fun ensureDroneSelectionTracked(trackedIds: Set<String>) {
        val current = _state.value.selectedDroneId ?: return
        if (current !in trackedIds) {
            lastFollowedPlacementKey = null
            _state.update { it.copy(selectedDroneId = null) }
            clearCameraFollow(CameraFollow.Kind.DRONE, current)
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
        val telemetry = demoDrones
            .firstOrNull { it.profile.id == selectedId }
            ?.telemetry
            ?: liveTelemetry[selectedId]
            ?: return

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
        socketHelper.connect(sessionIdentityProvider = { mapInteract.sessionIdentity(campusId) }) {
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
            clearCameraFollow(CameraFollow.Kind.DRONE, id)
            return
        }
        lastFollowedPlacementKey = null
        _state.update {
            it.copy(
                selectedDroneId = id,
                cameraFollow = CameraFollow(CameraFollow.Kind.DRONE, id, ++followNonce),
            )
        }
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

    /** Inclusive pixel bounds for demo flight (mosaic / campus content). */
    private data class DemoPlayArea(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    ) {
        val width: Float get() = (maxX - minX).coerceAtLeast(1f)
        val height: Float get() = (maxY - minY).coerceAtLeast(1f)

        fun clamp(point: PointF): PointF = PointF(
            point.x.coerceIn(minX, maxX),
            point.y.coerceIn(minY, maxY),
        )
    }

    private enum class DemoPlacement {
        OUTDOOR,
        FLOOR_1,
        FLOOR_2,
        TOURING,
    }

    private enum class DemoPathStyle {
        OVAL_HORIZONTAL,
        OVAL_VERTICAL,
        CROSS_PLUS,
        CROSS_X,
    }

    /** Per-drone runtime for the synthetic fleet. */
    private class DemoDroneActor(
        val profile: DemoDroneProfile,
        var angleDeg: Double = profile.startAngleDeg,
        var tourIndex: Int = 0,
        var tourStartedAtMs: Long = 0L,
        /** Non-null for outdoor / floor-1 / floor-2; touring resolves dynamically. */
        var fixedHome: DemoWaypoint? = null,
        var telemetry: DroneTelemetry? = null,
    )

    /**
     * Static look for one demo unit — path style + outdoor orbit center; placement
     * controls which floor telemetry reports (outdoor view still shows all four).
     */
    private data class DemoDroneProfile(
        val id: String,
        val placement: DemoPlacement,
        val pathStyle: DemoPathStyle,
        val droneIp: String,
        val state: String,
        val batteryPercent: Int,
        val confidence: Double,
        val centerXFrac: Float,
        val centerYFrac: Float,
        val radiusFrac: Float,
        val orbitDegPerSec: Double,
        val startAngleDeg: Double,
        val altitudeOffsetMeters: Double,
        val videoDeviceStatus: String,
        val micDeviceStatus: String,
        val audioDeviceStatus: String,
        val halowConnectivityStatus: String,
        val gyroStatus: String,
        val accStatus: String,
        val barometerStatus: String,
        val escStatus: String,
        val batteryUnitStatus: String,
        val motorStatus: String,
        /** Demo stand-in for `pilot_id`: this unit reports the logged-in user as its pilot. */
        val pilotedByCurrentUser: Boolean = false,
    )

    companion object {
        private const val TAG = "MapSessionVM"
        /** First demo id kept for any callers that still reference a single demo unit. */
        const val DEMO_DRONE_ID = "DEMO-01"
        /** Touring drone dwell on each outdoor/floor stop. */
        private const val DEMO_FLOOR_DWELL_MS = 8_000L
        /** Mithril UserRole.LAW_ENFORCEMENT_LABEL. */
        private const val LAW_ENFORCEMENT_ROLE = "Law Enforcement"

        /** Trailing digits of a threat name, for natural sorting. */
        private val TRAILING_NUMBER = Regex("(\\d+)$")

        /** Mithril MapViewModel.onThreatAdded hides the overlay after 5 s. */
        private const val THREAT_BANNER_DURATION_MS = 5_000L
        /** Keep paths off mosaic letterbox when campus footprints are unavailable. */
        private const val DEMO_WORLD_INSET_FRAC = 0.14f
        /** Expand building/annotation bounds so orbits have room without leaving campus. */
        private const val DEMO_FOOTPRINT_PAD_FRAC = 0.22f
        private val SQRT_2 = sqrt(2.0).toFloat()

        /** Simulated drones for demos without hardware. Off: the list shows the campus API drones. */
        private const val DEMO_FLEET_ENABLED = false

        private val DEMO_FLEET = listOf(
            // Stays outdoor — horizontal oval in NW campus quadrant.
            DemoDroneProfile(
                id = "DEMO-01",
                placement = DemoPlacement.OUTDOOR,
                pilotedByCurrentUser = true,
                pathStyle = DemoPathStyle.OVAL_HORIZONTAL,
                droneIp = "10.0.0.11",
                state = "ACTIVE",
                batteryPercent = 87,
                confidence = 0.94,
                centerXFrac = 0.32f,
                centerYFrac = 0.38f,
                radiusFrac = 0.12f,
                orbitDegPerSec = 26.0,
                startAngleDeg = 0.0,
                altitudeOffsetMeters = 0.0,
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
            ),
            // Floor 1 — vertical oval in NE quadrant.
            DemoDroneProfile(
                id = "DEMO-02",
                placement = DemoPlacement.FLOOR_1,
                pathStyle = DemoPathStyle.OVAL_VERTICAL,
                droneIp = "10.0.0.12",
                state = "ACTIVE",
                batteryPercent = 64,
                confidence = 0.88,
                centerXFrac = 0.68f,
                centerYFrac = 0.34f,
                radiusFrac = 0.11f,
                orbitDegPerSec = 32.0,
                startAngleDeg = 90.0,
                altitudeOffsetMeters = 0.4,
                videoDeviceStatus = "OK",
                micDeviceStatus = "OK",
                audioDeviceStatus = "DEGRADED",
                halowConnectivityStatus = "OK",
                gyroStatus = "OK",
                accStatus = "OK",
                barometerStatus = "WARN",
                escStatus = "OK",
                batteryUnitStatus = "OK",
                motorStatus = "OK",
            ),
            // Floor 2 (or outdoor fallback) — + cross in SW quadrant.
            DemoDroneProfile(
                id = "DEMO-03",
                placement = DemoPlacement.FLOOR_2,
                pathStyle = DemoPathStyle.CROSS_PLUS,
                droneIp = "10.0.0.13",
                state = "HOVER",
                batteryPercent = 41,
                confidence = 0.81,
                centerXFrac = 0.36f,
                centerYFrac = 0.66f,
                radiusFrac = 0.11f,
                orbitDegPerSec = 48.0,
                startAngleDeg = 0.0,
                altitudeOffsetMeters = -0.3,
                videoDeviceStatus = "OK",
                micDeviceStatus = "WARN",
                audioDeviceStatus = "OK",
                halowConnectivityStatus = "WEAK",
                gyroStatus = "OK",
                accStatus = "OK",
                barometerStatus = "OK",
                escStatus = "OK",
                batteryUnitStatus = "WARN",
                motorStatus = "OK",
            ),
            // Tours / outdoor — X cross in SE quadrant.
            DemoDroneProfile(
                id = "DEMO-04",
                placement = DemoPlacement.TOURING,
                pathStyle = DemoPathStyle.CROSS_X,
                droneIp = "10.0.0.14",
                state = "ACTIVE",
                batteryPercent = 23,
                confidence = 0.76,
                centerXFrac = 0.70f,
                centerYFrac = 0.64f,
                radiusFrac = 0.10f,
                orbitDegPerSec = 54.0,
                startAngleDeg = 45.0,
                altitudeOffsetMeters = 0.7,
                videoDeviceStatus = "DEGRADED",
                micDeviceStatus = "OK",
                audioDeviceStatus = "OK",
                halowConnectivityStatus = "OK",
                gyroStatus = "WARN",
                accStatus = "OK",
                barometerStatus = "OK",
                escStatus = "WARN",
                batteryUnitStatus = "LOW",
                motorStatus = "DEGRADED",
            ),
        )
    }
}

private fun com.guardianangel.dronetelementry.domain.model.DroneTelemetry.toMapPose(
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
    pilotId = pilotId,
    pilotName = pilotName,
    pilotEmail = pilotEmail,
    pilotHexColor = pilotHexColor,
)

/** List row for a campus drone that is not reporting telemetry: API battery + status only. */
private fun CampusDroneDto.toRosterPose(): DroneMapPose = DroneMapPose(
    droneDisplayId = displayId,
    deviceId = id,
    pixelX = 0f,
    pixelY = 0f,
    headingDegrees = 0f,
    batteryPercent = batteryLevel ?: 0,
    state = status,
    positionX = 0.0,
    positionY = 0.0,
    positionZ = 0.0,
    pilotId = pilotId,
)
