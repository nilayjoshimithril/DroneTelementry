package com.guardianangel.dronetelementry.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianangel.dronetelementry.data.model.MapConfigResponseDto
import com.guardianangel.dronetelementry.domain.interact.MapInteract
import com.guardianangel.dronetelementry.map.MapLayerSource
import com.guardianangel.dronetelementry.map.layerCols
import com.guardianangel.dronetelementry.map.layerOverviewUrl
import com.guardianangel.dronetelementry.map.layerRows
import com.guardianangel.dronetelementry.map.layerTileHeight
import com.guardianangel.dronetelementry.map.layerTileWidth
import com.guardianangel.dronetelementry.map.layerTiles
import com.guardianangel.dronetelementry.presentation.map.MosaicLayerConfig
import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class ZoomPreviewUiState(
    val loading: Boolean = true,
    val mosaic: MosaicLayerConfig? = null,
    val mapUnavailable: Boolean = false,
    val error: String? = null,
    val zoom: Float = MapInteract.DEFAULT_MAP_ZOOM,
    val saving: Boolean = false,
)

sealed interface ZoomPreviewEvent {
    data object Saved : ZoomPreviewEvent
    data class SaveFailed(val message: String) : ZoomPreviewEvent
}

/** Settings zoom preview: one campus's outdoor map + the zoom being chosen. */
class ZoomPreviewViewModel(
    private val mapInteract: MapInteract,
) : ViewModel() {

    private val _state = MutableStateFlow(ZoomPreviewUiState())
    val state: StateFlow<ZoomPreviewUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ZoomPreviewEvent>()
    val events: SharedFlow<ZoomPreviewEvent> = _events.asSharedFlow()

    private var campusId: String? = null

    fun load(campusId: String, force: Boolean = false) {
        if (!force && this.campusId == campusId) return
        this.campusId = campusId
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, mapUnavailable = false) }
            val savedZoom = runCatching { mapInteract.mapZoomLevel() }
                .getOrDefault(MapInteract.DEFAULT_MAP_ZOOM)
            runCatching { mapInteract.loadMapConfig(campusId) }
                .onSuccess { config ->
                    val mosaic = previewLayer(config)
                    _state.update {
                        it.copy(
                            loading = false,
                            mosaic = mosaic,
                            mapUnavailable = mosaic == null,
                            zoom = savedZoom,
                        )
                    }
                }
                .onFailure { e ->
                    AppLog.e(TAG, "Zoom preview map load failed campus=$campusId", e)
                    val notFound = (e as? HttpException)?.code() == 404 ||
                        e.message?.contains("not found", ignoreCase = true) == true
                    _state.update {
                        it.copy(
                            loading = false,
                            mapUnavailable = notFound,
                            error = if (notFound) null else e.message ?: "Failed to load campus map",
                            zoom = savedZoom,
                        )
                    }
                }
        }
    }

    fun retry() {
        campusId?.let { load(it, force = true) }
    }

    fun setZoom(level: Float) {
        val clamped = level.coerceIn(MapInteract.MIN_MAP_ZOOM, MapInteract.MAX_MAP_ZOOM)
        _state.update { it.copy(zoom = clamped) }
    }

    fun resetZoom() = setZoom(MapInteract.DEFAULT_MAP_ZOOM)

    fun save() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching { mapInteract.saveMapZoomLevel(_state.value.zoom) }
                .onSuccess {
                    _state.update { it.copy(saving = false) }
                    _events.emit(ZoomPreviewEvent.Saved)
                }
                .onFailure { e ->
                    AppLog.e(TAG, "Save zoom failed", e)
                    _state.update { it.copy(saving = false) }
                    _events.emit(ZoomPreviewEvent.SaveFailed(e.message ?: "Save failed"))
                }
        }
    }

    /** Outdoor campus map; if a campus only has building plans, its first floor. */
    private fun previewLayer(config: MapConfigResponseDto): MosaicLayerConfig? {
        val outdoor = config.outdoor?.let { MapLayerSource.Outdoor(it) }
        val layer = outdoor
            ?: config.buildings.firstNotNullOfOrNull { b -> b.floors.firstOrNull() }
                ?.let { MapLayerSource.Floor(it) }
            ?: return null
        val tiles = layer.layerTiles()
        if (tiles.isEmpty() && layer.layerOverviewUrl().isNullOrBlank()) return null
        return MosaicLayerConfig(
            cols = layer.layerCols().coerceAtLeast(1),
            rows = layer.layerRows().coerceAtLeast(1),
            tileWidth = layer.layerTileWidth(),
            tileHeight = layer.layerTileHeight(),
            tiles = tiles,
            overviewUrl = layer.layerOverviewUrl(),
            layerKey = "zoom_preview_${campusId}_${if (outdoor != null) "outdoor" else "floor"}",
        )
    }

    companion object {
        private const val TAG = "ZoomPreviewVM"
    }
}
