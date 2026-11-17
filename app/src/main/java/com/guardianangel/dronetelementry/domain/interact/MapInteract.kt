package com.guardianangel.dronetelementry.domain.interact

import com.guardianangel.dronetelementry.data.local.SessionStore
import com.guardianangel.dronetelementry.data.model.CampusDroneDto
import com.guardianangel.dronetelementry.data.model.MapConfigResponseDto
import com.guardianangel.dronetelementry.data.model.MosaicAnnotationDto
import com.guardianangel.dronetelementry.data.model.WsTicketRequestDto
import com.guardianangel.dronetelementry.data.remote.ApiService
import com.guardianangel.dronetelementry.utils.AppLog

class MapInteract(
    private val api: ApiService,
    private val sessionStore: SessionStore,
) {
    /**
     * Mithril `session-detail` publish payload: logged-in user id + the role on [campusId]
     * (the incident's campus), falling back to the user-level role.
     */
    suspend fun sessionIdentity(campusId: String): Pair<String?, String?> {
        val user = sessionStore.getAccessTokenResponse()?.userDetail ?: return null to null
        val role = user.campuses.orEmpty()
            .find { it.id.equals(campusId, ignoreCase = true) }?.roleName
            ?: user.role
        return user.id.takeIf { it.isNotBlank() } to role
    }

    suspend fun loadMapConfig(campusId: String): MapConfigResponseDto {
        AppLog.i(TAG, "API GET campuses/maps/$campusId")
        val response = api.getCampusMap(campusId)
        val outdoor = response.data?.outdoor
        AppLog.d(
            TAG,
            "map-config error=${response.error} message=${response.message} " +
                "campus=${response.data?.campusName} " +
                "outdoor=${outdoor?.cols}x${outdoor?.rows} tiles=${outdoor?.tiles?.size} " +
                "buildings=${response.data?.buildings?.size}",
        )
        if (response.error == true || response.data == null) {
            throw IllegalStateException(response.message ?: "Failed to load campus map")
        }
        return response.data
    }

    /** Loads all annotation pages (Mithril MapViewModel pagination). */
    suspend fun loadAnnotations(campusId: String): List<MosaicAnnotationDto> {
        val all = mutableListOf<MosaicAnnotationDto>()
        var page = 1
        var totalPages = 1
        do {
            AppLog.i(TAG, "API GET annotations?campus_id=$campusId&page=$page")
            val response = api.getAnnotations(campusId, page = page)
            if (response.error == true) {
                throw IllegalStateException(response.message ?: "Failed to load annotations")
            }
            val data = response.data
            val chunk = data?.data.orEmpty()
            all += chunk
            val meta = data?.meta
            val limit = meta?.limit?.takeIf { it > 0 } ?: chunk.size.coerceAtLeast(1)
            val total = meta?.total ?: all.size
            val currentPage = meta?.page?.takeIf { it > 0 } ?: page
            totalPages = ((total + limit - 1) / limit).coerceAtLeast(1)
            AppLog.d(
                TAG,
                "annotations page=$currentPage/$totalPages limit=$limit total=$total " +
                    "chunk=${chunk.size} accumulated=${all.size}",
            )
            page = currentPage + 1
        } while (page <= totalPages)

        AppLog.i(TAG, "annotations loaded total=${all.size}")
        return all
    }

    /**
     * Every drone of [campusId], walking all pages of `campuses/drones/` (large page size, so
     * normally one request). Rows for other campuses are dropped in case the filter is ignored.
     */
    suspend fun loadCampusDrones(campusId: String): List<CampusDroneDto> {
        val all = mutableListOf<CampusDroneDto>()
        var page = 1
        var totalPages: Int
        do {
            AppLog.i(TAG, "API GET campuses/drones/?campus_id=$campusId&page=$page&limit=$DRONE_PAGE_SIZE")
            val response = api.getCampusDrones(campusId, page = page, limit = DRONE_PAGE_SIZE)
            if (response.error == true) {
                throw IllegalStateException(response.message ?: "Failed to load drones")
            }
            val chunk = response.data?.data.orEmpty()
            all += chunk
            val meta = response.data?.meta
            val limit = meta?.limit?.takeIf { it > 0 } ?: chunk.size.coerceAtLeast(1)
            val total = meta?.total ?: all.size
            val currentPage = meta?.page?.takeIf { it > 0 } ?: page
            totalPages = ((total + limit - 1) / limit).coerceAtLeast(1)
            AppLog.d(TAG, "drones page=$currentPage/$totalPages total=$total chunk=${chunk.size}")
            // Guard against a server that keeps returning the same page.
            if (chunk.isEmpty()) break
            page = currentPage + 1
        } while (page <= totalPages)

        val campusDrones = all
            .filter { it.campusId.isNullOrBlank() || it.campusId.equals(campusId, ignoreCase = true) }
            .distinctBy { it.id.ifBlank { it.displayId } }
        AppLog.i(TAG, "drones loaded campus=$campusId count=${campusDrones.size} (fetched ${all.size})")
        return campusDrones
    }

    /** Saved default map zoom (multiple of "fit campus"), or [DEFAULT_MAP_ZOOM]. */
    suspend fun mapZoomLevel(): Float = sessionStore.getMapZoomLevel() ?: DEFAULT_MAP_ZOOM

    /** Persist the default map zoom (clamped to [MIN_MAP_ZOOM]..[MAX_MAP_ZOOM]) for every campus. */
    suspend fun saveMapZoomLevel(level: Float) {
        val clamped = level.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        AppLog.i(TAG, "Save map zoom level=$clamped")
        sessionStore.saveMapZoomLevel(clamped)
    }

    suspend fun refreshWsTicket(incidentId: String): String {
        AppLog.i(TAG, "API POST incidents/ws-ticket incident_id=$incidentId")
        val response = api.getWsTicket(WsTicketRequestDto(incidentId))
        AppLog.d(
            TAG,
            "ws-ticket error=${response.error} message=${response.message} " +
                "ticket=${AppLog.redact(response.data?.ticket)}",
        )
        if (response.error == true || response.data?.ticket.isNullOrBlank()) {
            throw IllegalStateException(response.message ?: "Failed to refresh ws ticket")
        }
        return response.data!!.ticket
    }

    companion object {
        private const val TAG = "MapApi"
        private const val DRONE_PAGE_SIZE = 100

        /** Map opens fitted to the whole campus. */
        const val DEFAULT_MAP_ZOOM = 1f
        const val MIN_MAP_ZOOM = 1f
        const val MAX_MAP_ZOOM = 6f
    }
}
