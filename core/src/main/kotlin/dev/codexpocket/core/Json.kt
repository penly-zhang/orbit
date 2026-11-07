package dev.codexpocket.core

import kotlinx.serialization.json.*

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun obj(vararg entries: Pair<String, Any?>): JsonObject = JsonObject(entries.associate { it.first to element(it.second) })
fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map(::element))
    else -> error("Unsupported JSON value")
}
fun JsonObject.text(key: String, default: String = ""): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.objectAt(key: String): JsonObject = get(key) as? JsonObject ?: obj()
