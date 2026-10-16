package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OngoingIncidentDto(
    @SerialName("ID") val id: String = "",
    @SerialName("campus_id") val campusId: String = "",
    @SerialName("campus_name") val campusName: String? = null,
    @SerialName("campus_number") val campusNumber: Int? = null,
    @SerialName("district_id") val districtId: String = "",
    @SerialName("district_name") val districtName: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("is_drill") val isDrill: Boolean? = null,
    @SerialName("drone_ids") val droneIds: List<String>? = null,
    @SerialName("CreatedAt") val createdAt: String? = null,
    @SerialName("max_floors_in_building") val maxFloorsInBuilding: Int? = null,
)
