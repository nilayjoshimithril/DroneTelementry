package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MainGenericResponse<T>(
    @SerialName("data") val data: T? = null,
    @SerialName("error") val error: Boolean? = null,
    @SerialName("message") val message: String? = null,
)
