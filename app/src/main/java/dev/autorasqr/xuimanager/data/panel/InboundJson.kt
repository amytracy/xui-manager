package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.data.panel.PanelJson.array
import dev.autorasqr.xuimanager.data.panel.PanelJson.bool
import dev.autorasqr.xuimanager.data.panel.PanelJson.int
import dev.autorasqr.xuimanager.data.panel.PanelJson.long
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builds inbound JSON in the shape 3x-ui's own inbound form produces (frontend/src/schemas/protocols at v3.10.2).
 * The inbound starts without clients; they are added through the clients API.
 */
object InboundJson {
    const val SS_METHOD = "2022-blake3-aes-256-gcm"

    fun protocolName(protocol: InboundProtocol): String = when (protocol) {
        InboundProtocol.VLESS -> "vless"
        InboundProtocol.TROJAN -> "trojan"
        InboundProtocol.VMESS -> "vmess"
        InboundProtocol.SHADOWSOCKS -> "shadowsocks"
    }

    fun networkName(transport: InboundTransport): String = when (transport) {
        InboundTransport.TCP -> "tcp"
        InboundTransport.XHTTP -> "xhttp"
        InboundTransport.GRPC -> "grpc"
        InboundTransport.WS -> "ws"
    }

    fun settings(spec: InboundSpec): JsonObject = buildJsonObject {
        put("clients", JsonArray(emptyList()))
        when (spec.protocol) {
            InboundProtocol.VLESS -> {
                put("decryption", "none")
                put("fallbacks", JsonArray(emptyList()))
            }
            InboundProtocol.TROJAN -> put("fallbacks", JsonArray(emptyList()))
            InboundProtocol.VMESS -> Unit
            InboundProtocol.SHADOWSOCKS -> {
                put("method", SS_METHOD)
                put("password", requireNotNull(spec.shadowsocksPassword) { "Shadowsocks 2022 needs a server key" })
                put("network", "tcp,udp")
                put("ivCheck", false)
            }
        }
    }

    fun streamSettings(spec: InboundSpec): JsonObject = buildJsonObject {
        put("network", networkName(spec.transport))
        val reality = spec.reality
        put("security", if (reality != null) "reality" else "none")
        put("externalProxy", JsonArray(emptyList()))
        if (reality != null) {
            putJsonObject("realitySettings") {
                put("show", false)
                put("xver", 0)
                put("target", reality.target)
                put("serverNames", buildJsonArray { add(JsonPrimitive(reality.serverName)) })
                put("privateKey", reality.privateKey)
                put("minClientVer", "")
                put("maxClientVer", "")
                put("maxTimediff", 0)
                put("shortIds", buildJsonArray { add(JsonPrimitive(reality.shortId)) })
                put("mldsa65Seed", "")
                putJsonObject("settings") {
                    put("publicKey", reality.publicKey)
                    put("fingerprint", reality.fingerprint)
                    put("serverName", "")
                    put("spiderX", "/")
                    put("mldsa65Verify", "")
                }
            }
        }
        when (spec.transport) {
            InboundTransport.TCP -> putJsonObject("tcpSettings") {
                put("acceptProxyProtocol", false)
                putJsonObject("header") { put("type", "none") }
            }
            InboundTransport.WS -> putJsonObject("wsSettings") {
                put("acceptProxyProtocol", false)
                put("path", spec.path)
                put("host", "")
                put("headers", JsonObject(emptyMap()))
                put("heartbeatPeriod", 0)
            }
            InboundTransport.GRPC -> putJsonObject("grpcSettings") {
                put("serviceName", spec.path.trimStart('/'))
                put("authority", "")
                put("multiMode", false)
            }
            InboundTransport.XHTTP -> putJsonObject("xhttpSettings") {
                put("path", spec.path)
                put("host", "")
                put("mode", "auto")
                put("xPaddingBytes", "100-1000")
            }
        }
    }

    fun sniffing(): JsonObject = buildJsonObject {
        put("enabled", true)
        put("destOverride", buildJsonArray { listOf("http", "tls", "quic").forEach { add(JsonPrimitive(it)) } })
        put("metadataOnly", false)
        put("routeOnly", false)
    }

    /** Fields 3x-ui stores as JSON; older panels send them as strings, v3 as nested objects. */
    val NESTED_FIELDS = listOf("settings", "streamSettings", "sniffing")

    /** Returns [raw] with [NESTED_FIELDS] as objects. */
    fun normalize(json: Json, raw: JsonObject): JsonObject = JsonObject(
        raw.toMutableMap().apply {
            NESTED_FIELDS.forEach { key -> PanelJson.objectOrNull(json, raw[key])?.let { put(key, it) } }
        },
    )

    /** Summary of a list/get entry; null when it has no id. */
    fun summary(json: Json, raw: JsonObject): PanelInbound? {
        val id = raw.int("id") ?: return null
        val settings = PanelJson.objectOrNull(json, raw["settings"])
        val stream = PanelJson.objectOrNull(json, raw["streamSettings"])
        val security = stream?.string("security") ?: "none"
        val clients = settings?.array("clients")?.size ?: raw.array("clientStats")?.size ?: 0
        return PanelInbound(
            id = id,
            remark = raw.string("remark").orEmpty(),
            port = raw.int("port") ?: 0,
            protocol = raw.string("protocol").orEmpty(),
            network = stream?.string("network") ?: "tcp",
            security = security,
            enabled = raw.bool("enable") ?: true,
            up = raw.long("up") ?: 0,
            down = raw.long("down") ?: 0,
            clientCount = clients,
            reality = if (security == "reality") (stream?.get("realitySettings") as? JsonObject)?.let(::realityInfo) else null,
            nodeId = raw.int("nodeId")?.takeIf { it > 0 },
        )
    }

    private fun realityInfo(reality: JsonObject): RealityInfo = RealityInfo(
        // Older panels call the target "dest".
        target = reality.string("target") ?: reality.string("dest").orEmpty(),
        serverNames = reality.array("serverNames").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        fingerprint = (reality["settings"] as? JsonObject)?.string("fingerprint").orEmpty(),
    )

    /**
     * Points an inbound's Reality at [target] with [serverName] as the only accepted SNI and [fingerprint] for client
     * links. Everything else (keys, short ids, clients) stays as it is.
     */
    fun patchReality(inbound: JsonObject, target: String, serverName: String, fingerprint: String): JsonObject {
        val stream = inbound["streamSettings"] as? JsonObject ?: error("streamSettings must be normalized first")
        val reality = stream["realitySettings"] as? JsonObject ?: error("not a Reality inbound")
        val clientSettings = (reality["settings"] as? JsonObject) ?: JsonObject(emptyMap())
        val newReality = JsonObject(
            reality.toMutableMap().apply {
                remove("dest")
                put("target", JsonPrimitive(target))
                put("serverNames", buildJsonArray { add(JsonPrimitive(serverName)) })
                put("settings", JsonObject(clientSettings + mapOf("fingerprint" to JsonPrimitive(fingerprint), "serverName" to JsonPrimitive(""))))
            },
        )
        return JsonObject(inbound + ("streamSettings" to JsonObject(stream + ("realitySettings" to newReality))))
    }

    /** For a JSON string field, the way 2.x expects settings in form posts. */
    fun asText(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> if (element.isString) element.content else element.toString()
        else -> element.toString()
    }
}
