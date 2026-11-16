package com.guardianangel.dronetelementry.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CampusesResponseDto(
    @SerialName("campuses") val campuses: List<CampusDto>? = null,
    /** Paginated like Mithril CampusesResponseDTO (limit / page / total). */
    @SerialName("meta") val meta: AnnotationMetaDto? = null,
)
