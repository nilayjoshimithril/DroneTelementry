package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CampusesResponseDto(
    @SerialName("campuses") val campuses: List<CampusDto>? = null,
)
