package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Helpers shared by the panel adapters for 3x-ui's {success, msg, obj} envelope and loosely typed JSON. */
internal object PanelJson {

    /** Returns obj, or null for an empty body. A {success:false} envelope becomes PanelError(msg). */
    fun unwrap(json: Json, text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw AppException(AppError.PanelError("unexpected response"), e)
        }
        val envelope = element as? JsonObject ?: return element
        if (envelope["success"]?.jsonPrimitive?.booleanOrNull == false) {
            val message = envelope.string("msg")?.takeIf { it.isNotBlank() } ?: "request failed"
            throw AppException(AppError.PanelError(message))
        }
        return envelope["obj"]
    }

    inline fun <reified T> decode(json: Json, element: JsonElement?): T = try {
        json.decodeFromJsonElement(element ?: JsonNull)
    } catch (e: SerializationException) {
        throw AppException(AppError.PanelError("unexpected response"), e)
    } catch (e: IllegalArgumentException) {
        throw AppException(AppError.PanelError("unexpected response"), e)
    }

    /** Accepts a nested object or, as older panels store it, a JSON-encoded string. */
    fun objectOrNull(json: Json, element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString && element.content.isNotBlank()) {
            runCatching { json.parseToJsonElement(element.content) as? JsonObject }.getOrNull()
        } else {
            null
        }
        else -> null
    }

    fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
}
