package io.github.alextherajunction.codexweb.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.string(name: String): String? {
    val value = this[name] as? JsonPrimitive ?: return null
    return value.contentOrNull
}

internal fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.obj(name: String): JsonObject? =
    this[name]?.let { if (it is JsonObject) it else null }

internal fun JsonObject.array(name: String): JsonArray? =
    this[name]?.let { if (it is JsonArray) it else null }

internal fun JsonElement.asObjectOrNull(): JsonObject? =
    if (this is JsonObject) this else null

internal fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.stringList(name: String): List<String> =
    array(name)?.mapNotNull { it.asStringOrNull() } ?: emptyList()

internal fun jsonString(value: String): JsonPrimitive = JsonPrimitive(value)
