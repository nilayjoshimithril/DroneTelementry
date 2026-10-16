package com.guardianangel.mithrildefense.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class MapTileDto(
    val col: Int,
    val row: Int,
    val url: String,
)

object MapTileListSerializer : KSerializer<List<MapTileDto>> {
    private val delegate = ListSerializer(MapTileDto.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<MapTileDto> {
        val jsonDecoder = decoder as? JsonDecoder ?: return emptyList()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> jsonDecoder.json.decodeFromJsonElement(delegate, element)
            is JsonObject -> element.mapNotNull { (key, value) ->
                val parts = key.split("_")
                if (parts.size != 2) return@mapNotNull null
                val col = parts[0].toIntOrNull() ?: return@mapNotNull null
                val row = parts[1].toIntOrNull() ?: return@mapNotNull null
                val url = (value as? JsonPrimitive)?.content ?: return@mapNotNull null
                MapTileDto(col, row, url)
            }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<MapTileDto>) {
        encoder.encodeSerializableValue(delegate, value)
    }
}
