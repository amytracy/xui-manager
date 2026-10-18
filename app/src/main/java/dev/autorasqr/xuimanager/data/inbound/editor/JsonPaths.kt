package dev.autorasqr.xuimanager.data.inbound.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A path into an inbound's JSON: object keys and array indexes, e.g. `streamSettings.tlsSettings.certificates.0.usage`.
 * The editor reads and writes every field through these, so keys the form does not know stay untouched.
 */
@JvmInline
value class JsonPath(val parts: List<Any>) {
    constructor(dotted: String) : this(dotted.split('.').map { it.toIntOrNull() ?: it })

    operator fun plus(child: Any): JsonPath = JsonPath(parts + child)
    operator fun plus(child: JsonPath): JsonPath = JsonPath(parts + child.parts)
    override fun toString() = parts.joinToString(".")
}

fun JsonElement?.at(path: JsonPath): JsonElement? {
    var current: JsonElement? = this
    for (part in path.parts) {
        current = when (part) {
            is Int -> (current as? JsonArray)?.getOrNull(part)
            else -> (current as? JsonObject)?.get(part as String)
        }
        if (current == null || current is JsonNull) return null
    }
    return current
}

/** Returns a copy with [value] at [path]; null removes the key (or element). Missing objects along the way are created. */
fun JsonObject.with(path: JsonPath, value: JsonElement?): JsonObject = setIn(this, path.parts, value) as JsonObject

private fun setIn(node: JsonElement?, parts: List<Any>, value: JsonElement?): JsonElement? {
    if (parts.isEmpty()) return value
    val head = parts.first()
    val rest = parts.drop(1)
    return when (head) {
        is Int -> {
            val list = (node as? JsonArray)?.toMutableList() ?: mutableListOf()
            while (list.size <= head) list += JsonObject(emptyMap())
            val updated = setIn(list[head], rest, value)
            if (updated == null) list.removeAt(head) else list[head] = updated
            JsonArray(list)
        }
        else -> {
            val map = (node as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val updated = setIn(map[head as String], rest, value)
            if (updated == null) map.remove(head) else map[head] = updated
            JsonObject(map)
        }
    }
}

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
fun JsonElement?.obj(): JsonObject? = this as? JsonObject

fun json(value: String) = JsonPrimitive(value)
fun json(value: Number) = JsonPrimitive(value)
fun json(value: Boolean) = JsonPrimitive(value)
fun jsonStrings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

/** Deep merge: keys of [defaults] missing in [target] are added, nested objects merged; existing values win. */
fun fillDefaults(target: JsonObject, defaults: JsonObject): JsonObject = JsonObject(
    buildMap {
        putAll(defaults)
        for ((key, value) in target) {
            val default = defaults[key]
            put(key, if (value is JsonObject && default is JsonObject) fillDefaults(value, default) else value)
        }
    },
)
