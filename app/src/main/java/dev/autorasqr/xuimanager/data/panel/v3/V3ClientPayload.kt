package dev.autorasqr.xuimanager.data.panel.v3

import dev.autorasqr.xuimanager.data.panel.ClientChanges
import dev.autorasqr.xuimanager.data.panel.PanelJson.int
import dev.autorasqr.xuimanager.data.panel.PanelJson.long
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * POST /clients/update replaces the whole client row, so the payload carries every field of the stored record
 * (from GET /clients/get) with the edited ones swapped in. Field mapping follows the panel's own
 * ClientFormModal: the record's `uuid` goes out as `id`, and its numeric row `id` is dropped.
 */
internal object V3ClientPayload {

    fun update(record: JsonObject, changes: ClientChanges): JsonObject = buildJsonObject {
        val uuid = record.string("uuid").orEmpty()
        put("email", changes.email)
        put("subId", changes.subId)
        put("id", uuid)
        put("uuid", uuid)
        put("password", record.string("password").orEmpty())
        put("auth", record.string("auth").orEmpty())
        put("flow", record.string("flow").orEmpty())
        put("security", record.string("security").orEmpty().ifEmpty { "auto" })
        put("totalGB", changes.totalBytes)
        put("expiryTime", changes.expiryTime)
        put("reset", record.int("reset") ?: 0)
        put("resetDay", record.int("resetDay") ?: 0)
        put("resetWeekday", record.int("resetWeekday") ?: 0)
        put("resetMax", record.int("resetMax") ?: 0)
        put("trafficReset", record.string("trafficReset").orEmpty().ifEmpty { "never" })
        put("trafficResetDay", record.int("trafficResetDay")?.takeIf { it in 1..31 } ?: 1)
        put("limitIp", record.int("limitIp") ?: 0)
        put("limitHwid", record.int("limitHwid") ?: 0)
        put("tgId", record.long("tgId") ?: 0L)
        put("group", record.string("group").orEmpty())
        put("comment", record.string("comment").orEmpty())
        put("enable", changes.enabled)

        (record["reverse"] as? JsonObject)?.string("tag")?.takeIf { it.isNotBlank() }?.let { tag ->
            put("reverse", buildJsonObject { put("tag", tag) })
        }
        // WireGuard / AmneziaWG peers and MTProto secrets, so editing such clients does not wipe their keys.
        record.string("privateKey")?.takeIf { it.isNotEmpty() }?.let { privateKey ->
            put("privateKey", privateKey)
            record.string("publicKey")?.let { put("publicKey", it) }
            record.string("preSharedKey")?.takeIf { it.isNotEmpty() }?.let { put("preSharedKey", it) }
            record.int("keepAlive")?.let { put("keepAlive", it) }
            val allowed = record.string("allowedIPs").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            if (allowed.isNotEmpty()) put("allowedIPs", buildJsonArray { allowed.forEach { add(JsonPrimitive(it)) } })
            record.string("forwardedPorts")?.takeIf { it.isNotEmpty() }?.let { put("forwardedPorts", it) }
        }
        record.string("secret")?.takeIf { it.isNotEmpty() }?.let { secret ->
            put("secret", secret)
            put("adTag", record.string("adTag").orEmpty())
        }
    }
}
