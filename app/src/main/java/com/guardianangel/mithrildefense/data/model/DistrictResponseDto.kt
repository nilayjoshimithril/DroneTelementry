package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DistrictResponseDto(
    @SerialName("ID") val id: String = "",
    @SerialName("address") val address: String = "",
    @SerialName("auth_url") val authUrl: String = "",
    @SerialName("city") val city: String = "",
    @SerialName("idp_detail") val idpDetail: String? = null,
    @SerialName("invite_code") val inviteCode: String = "",
    @SerialName("logo_url") val logoUrl: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("state") val state: String = "",
    @SerialName("zip_code") val zipCode: String = "",
)
