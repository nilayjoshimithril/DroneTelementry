package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mithril [ThreatSocketPayload] — `threat-added` / `threat-removed` / `on-session-detail.threats[]`. */
@Serializable
data class ThreatDto(
    @SerialName("threat_id") val threatId: String = "",
    @SerialName("threat_name") val threatName: String? = null,
    val building: List<String>? = null,
    val floors: List<String>? = null,
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null,
    val timestamp: String? = null,
)
