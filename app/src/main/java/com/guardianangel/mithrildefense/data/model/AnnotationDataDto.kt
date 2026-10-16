package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AnnotationDataDto(
    val data: List<MosaicAnnotationDto> = emptyList(),
    val meta: AnnotationMetaDto = AnnotationMetaDto(),
)
