package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    @SerialName("ID") val id: String = "",
    @SerialName("email") val email: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("campuses") val campuses: List<UserCampusDto>? = null,
    @SerialName("districts") val districts: List<UserDistrictDto>? = null,
)
