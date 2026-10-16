package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AnnotationMetaDto(
    val limit: Int = 0,
    val page: Int = 0,
    val total: Int = 0,
)
