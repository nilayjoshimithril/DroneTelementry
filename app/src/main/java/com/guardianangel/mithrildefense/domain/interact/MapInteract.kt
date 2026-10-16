package com.guardianangel.mithrildefense.domain.interact

import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.MosaicAnnotationDto
import com.guardianangel.mithrildefense.data.model.WsTicketRequestDto
import com.guardianangel.mithrildefense.data.remote.ApiService
import com.guardianangel.mithrildefense.utils.AppLog

class MapInteract(
    private val api: ApiService,
) {
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
    }
}
