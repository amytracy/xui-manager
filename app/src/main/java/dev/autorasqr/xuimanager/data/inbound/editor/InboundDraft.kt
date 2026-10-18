package dev.autorasqr.xuimanager.data.inbound.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The inbound being edited, as the JSON the panel stores (settings, streamSettings and sniffing as nested objects).
 * Every edit goes through [set] on a path, so keys the editor does not know are carried through untouched.
 * The rules for switching protocol, transport and security follow 3x-ui v3.10.2's InboundFormModal.
 */
data class InboundDraft(val inbound: JsonObject, val isNew: Boolean) {

    val protocol: String get() = inbound["protocol"].str() ?: "vless"
    val network: String get() = inbound.at(P_NETWORK).str().orEmpty()
    val security: String get() = inbound.at(P_SECURITY).str() ?: "none"

    /** The node hosting the inbound (3x-ui v3); null for the panel's own server. */
    val nodeId: Int? get() = inbound["nodeId"].int()?.takeIf { it > 0 }

    operator fun get(path: JsonPath): JsonElement? = inbound.at(path)

    fun set(path: JsonPath, value: JsonElement?): InboundDraft {
        var next = copy(inbound = inbound.with(path, value))
        if (path == P_AWG_PRIVATE) next = next.syncAwgPublicKey()
        return next.withAutoTag(this)
    }

    /**
     * Add mode only, like the panel's "Deploy to". Links of a node's inbound advertise the node's address by default;
     * back on the panel's own server the "node" strategy falls back to "listen".
     */
    fun withNode(id: Int?): InboundDraft {
        if (!isNew) return this
        val target = id?.takeIf { protocol in EditorDefaults.NODE_PROTOCOLS }
        var json = inbound.with(JsonPath("nodeId"), target?.let { json(it) })
        val strategy = json["shareAddrStrategy"].str()
        if (target != null && strategy != "custom") json = json.with(JsonPath("shareAddrStrategy"), json("node"))
        if (target == null && (strategy == null || strategy == "node")) json = json.with(JsonPath("shareAddrStrategy"), json("listen"))
        return copy(inbound = json).withAutoTag(this)
    }

    /** Add mode only, as in 3x-ui: the protocol of a saved inbound cannot change. */
    fun withProtocol(next: String): InboundDraft {
        if (!isNew || next == protocol) return this
        var json = inbound.with(JsonPath("protocol"), json(next))
            .with(P_SETTINGS, EditorDefaults.protocolSettings(next))
        if (next != "vless") json = json.with(JsonPath("disableFlow"), json(false))
        val stream: JsonObject? = when (next) {
            "hysteria" -> JsonObject(
                mapOf(
                    "network" to json("hysteria"),
                    "security" to json("tls"),
                    "hysteriaSettings" to EditorDefaults.streamSlice("hysteria"),
                    "tlsSettings" to EditorDefaults.quicTlsSettings(),
                    "finalmask" to JsonObject(
                        mapOf(
                            "tcp" to JsonArray(emptyList()),
                            "udp" to JsonArray(listOf(JsonObject(mapOf("type" to json("salamander"), "settings" to EditorDefaults.udpMaskSettings("salamander"))))),
                        ),
                    ),
                ),
            )
            "masque" -> JsonObject(
                mapOf(
                    "network" to json("masque"),
                    "security" to json("tls"),
                    "masqueSettings" to JsonObject(mapOf("path" to json(EditorDefaults.MASQUE_DEFAULT_PATH))),
                    "tlsSettings" to EditorDefaults.quicTlsSettings(),
                ),
            )
            "wireguard", "tunnel" -> JsonObject(mapOf("security" to json("none")))
            else -> {
                val current = network
                if (current == "hysteria" || current == "masque" || current.isEmpty()) {
                    JsonObject(mapOf("network" to json("tcp"), "security" to json("none"), "tcpSettings" to EditorDefaults.streamSlice("tcp")))
                } else {
                    null
                }
            }
        }
        if (stream != null) json = json.with(P_STREAM, stream)
        val next = copy(inbound = json).withAutoTag(this)
        // Only some protocols can live on a node (node-protocols.ts); the others stay on the panel's server.
        return if (nodeId != null && next.protocol !in EditorDefaults.NODE_PROTOCOLS) next.withNode(null) else next
    }

    /** onNetworkChange: drop the old network's settings, seed the new one, keep finalmask's mkcp-legacy mask in step. */
    fun withNetwork(next: String): InboundDraft {
        val current = inbound.at(P_STREAM).obj() ?: JsonObject(emptyMap())
        val cleaned = current.toMutableMap()
        cleaned["network"] = json(next)
        EditorDefaults.NETWORK_SLICE_KEYS.filter { it != "${next}Settings" }.forEach(cleaned::remove)
        if (next == "xdrive") {
            // The core would read the inbound's TLS as the storage API's client TLS.
            cleaned["security"] = json("none")
            cleaned.remove("tlsSettings")
            cleaned.remove("realitySettings")
        } else {
            cleaned.remove("address")
            cleaned.remove("port")
        }
        cleaned["${next}Settings"] = EditorDefaults.streamSlice(next)
        val finalmask = cleaned["finalmask"].obj()
        val udp = finalmask?.get("udp") as? JsonArray ?: JsonArray(emptyList())
        if (next == "kcp") {
            if (udp.none { it.obj()?.get("type").str() == "mkcp-legacy" }) {
                val mkcp = JsonObject(mapOf("type" to json("mkcp-legacy"), "settings" to EditorDefaults.udpMaskSettings("mkcp-legacy")))
                cleaned["finalmask"] = JsonObject((finalmask ?: JsonObject(emptyMap())) + ("udp" to JsonArray(udp + mkcp)))
            }
        } else if (finalmask != null && finalmask["udp"] is JsonArray) {
            cleaned["finalmask"] = JsonObject(finalmask + ("udp" to JsonArray(udp.filter { it.obj()?.get("type").str() != "mkcp-legacy" })))
        }
        // WebSocket needs HTTP/1.1 for its handshake.
        if (next == "ws" && cleaned["security"].str() == "tls") {
            val tls = cleaned["tlsSettings"].obj() ?: JsonObject(emptyMap())
            cleaned["tlsSettings"] = JsonObject(tls + ("alpn" to jsonStrings(listOf("http/1.1"))))
        }
        return copy(inbound = inbound.with(P_STREAM, JsonObject(cleaned))).withAutoTag(this)
    }

    /** onSecurityChange; for Reality the caller fills the key pair from the panel afterwards. */
    fun withSecurity(next: String): InboundDraft {
        val current = inbound.at(P_STREAM).obj() ?: JsonObject(emptyMap())
        val cleaned = current.toMutableMap()
        cleaned["security"] = json(next)
        cleaned.remove("tlsSettings")
        cleaned.remove("realitySettings")
        when (next) {
            "tls" -> cleaned["tlsSettings"] = EditorDefaults.tlsSettings(current["network"].str())
            "reality" -> cleaned["realitySettings"] = EditorDefaults.realitySettings()
        }
        return copy(inbound = inbound.with(P_STREAM, JsonObject(cleaned)))
    }

    /** sockopt on/off (toggleSockopt). */
    fun withSockopt(on: Boolean): InboundDraft = set(JsonPath("streamSettings.sockopt"), if (on) EditorDefaults.SOCKOPT else null)

    /** AmneziaWG stores the server public key; it must follow the private key even when typed by hand. */
    private fun syncAwgPublicKey(): InboundDraft {
        if (protocol != "amneziawg") return this
        val public = EditorDefaults.wireguardPublicKey(inbound.at(P_AWG_PRIVATE).str().orEmpty()).orEmpty()
        return copy(inbound = inbound.with(JsonPath("settings.server.publicKey"), json(public)))
    }

    /** Keeps an automatic tag (blank or in-<port>-<transport>) in step with the port and transport, as the panel does. */
    private fun withAutoTag(before: InboundDraft): InboundDraft {
        val tag = inbound["tag"].str().orEmpty()
        if (!InboundTag.isAuto(tag, before)) return this
        val next = InboundTag.compose(this)
        return if (next == tag) this else copy(inbound = inbound.with(JsonPath("tag"), json(next)))
    }

    companion object {
        val P_SETTINGS = JsonPath("settings")
        val P_STREAM = JsonPath("streamSettings")
        val P_NETWORK = JsonPath("streamSettings.network")
        val P_SECURITY = JsonPath("streamSettings.security")
        val P_SNIFFING = JsonPath("sniffing")
        private val P_AWG_PRIVATE = JsonPath("settings.server.privateKey")

        /** buildAddModeValues: VLESS over RAW without security, random port, auto tag. */
        fun new(port: Int): InboundDraft {
            val inbound = JsonObject(
                mapOf(
                    "enable" to json(true),
                    "remark" to json(""),
                    "protocol" to json("vless"),
                    "listen" to json(""),
                    "port" to json(port),
                    "tag" to json(""),
                    "total" to json(0),
                    "expiryTime" to json(0),
                    "trafficReset" to json("never"),
                    "trafficResetDay" to json(1),
                    "shareAddrStrategy" to json("listen"),
                    "shareAddr" to json(""),
                    "subSortIndex" to json(1),
                    "excludeFromSub" to json(false),
                    "disableFlow" to json(false),
                    "settings" to EditorDefaults.protocolSettings("vless"),
                    "streamSettings" to JsonObject(
                        mapOf("network" to json("tcp"), "security" to json("none"), "tcpSettings" to EditorDefaults.streamSlice("tcp")),
                    ),
                    "sniffing" to EditorDefaults.SNIFFING,
                ),
            )
            val draft = InboundDraft(inbound, isNew = true)
            return draft.copy(inbound = draft.inbound.with(JsonPath("tag"), json(InboundTag.compose(draft))))
        }

        /**
         * rawInboundToFormValues for an inbound from [dev.autorasqr.xuimanager.data.panel.PanelApi.getInbound]: heals legacy
         * shapes and fills schema defaults so every field shows a value. Clients are left out: they have their own API.
         */
        fun fromPanel(raw: JsonObject): InboundDraft {
            var json = raw
            for (key in listOf("id", "clientStats", "userId", "fallbackParent", "originNodeGuid")) json = json.with(JsonPath(key), null)
            val protocol = json["protocol"].str().orEmpty()
            var settings = json.at(P_SETTINGS).obj() ?: JsonObject(emptyMap())
            settings = fillDefaults(settings, EditorDefaults.settingsSchemaDefaults(protocol)).with(JsonPath("clients"), null)
            json = json.with(P_SETTINGS, settings)

            json.at(P_STREAM).obj()?.let { original ->
                var stream = original
                // Old rows call the network "method".
                val method = stream["method"].str()
                if (method != null) {
                    stream = stream.with(JsonPath("method"), null)
                    if (method.isNotEmpty() && stream["network"] == null) stream = stream.with(JsonPath("network"), json(method))
                }
                val network = stream["network"].str().orEmpty()
                if (network.isNotEmpty()) {
                    val key = JsonPath("${network}Settings")
                    var slice = stream.at(key).obj() ?: JsonObject(emptyMap())
                    if (network == "xhttp") slice = migrateLegacyXhttp(slice)
                    stream = stream.with(key, fillDefaults(slice, EditorDefaults.streamSlice(network)))
                }
                when (stream["security"].str()) {
                    "tls" -> {
                        val tls = fillDefaults(stream.at(JsonPath("tlsSettings")).obj() ?: JsonObject(emptyMap()), EditorDefaults.tlsSchemaDefaults())
                        val certs = tls["certificates"] as? JsonArray ?: JsonArray(emptyList())
                        stream = stream.with(JsonPath("tlsSettings"), tls.with(JsonPath("certificates"), JsonArray(certs.map { synthesizeUseFile(it) })))
                    }
                    "reality" -> {
                        var reality = stream.at(JsonPath("realitySettings")).obj() ?: JsonObject(emptyMap())
                        val dest = reality["dest"].str()
                        if (reality["target"].str().isNullOrEmpty() && !dest.isNullOrEmpty()) reality = reality.with(JsonPath("target"), json(dest))
                        reality = reality.with(JsonPath("dest"), null)
                        stream = stream.with(JsonPath("realitySettings"), fillDefaults(reality, EditorDefaults.realitySchemaDefaults()))
                    }
                }
                json = json.with(P_STREAM, stream)
            }
            val sniffing = json.at(P_SNIFFING).obj() ?: JsonObject(emptyMap())
            json = json.with(P_SNIFFING, fillDefaults(sniffing, EditorDefaults.SNIFFING))
            val topDefaults = mapOf(
                "remark" to json(""), "listen" to json(""), "tag" to json(""), "enable" to json(true), "total" to json(0),
                "expiryTime" to json(0), "trafficReset" to json("never"), "trafficResetDay" to json(1), "shareAddrStrategy" to json("node"),
                "shareAddr" to json(""), "subSortIndex" to json(1), "excludeFromSub" to json(false), "disableFlow" to json(false),
            )
            json = fillDefaults(json, JsonObject(topDefaults))
            return InboundDraft(json, isNew = false)
        }

        private fun synthesizeUseFile(cert: JsonElement): JsonElement {
            val obj = cert.obj() ?: return cert
            if (obj["useFile"] is JsonPrimitive) return obj
            val hasInline = obj["certificate"].strings().isNotEmpty() || obj["key"].strings().isNotEmpty()
            val useFile = !obj["certificateFile"].str().isNullOrEmpty() || !obj["keyFile"].str().isNullOrEmpty() || !hasInline
            return fillDefaults(obj.with(JsonPath("useFile"), json(useFile)), EditorDefaults.tlsCertificate())
        }

        private fun migrateLegacyXhttp(slice: JsonObject): JsonObject {
            var out = slice
            if (out["sessionIDPlacement"] == null && out["sessionPlacement"] != null) out = out.with(JsonPath("sessionIDPlacement"), out["sessionPlacement"])
            if (out["sessionIDKey"] == null && out["sessionKey"] != null) out = out.with(JsonPath("sessionIDKey"), out["sessionKey"])
            return out.with(JsonPath("sessionPlacement"), null).with(JsonPath("sessionKey"), null)
        }
    }
}

/** inbound-tag.ts: in-<port>-<tcp|udp|tcpudp>. */
object InboundTag {
    private const val TCP = 1
    private const val UDP = 2

    fun compose(draft: InboundDraft): String {
        val port = draft.inbound["port"].int() ?: 0
        val suffix = when (transports(draft)) {
            TCP -> "tcp"
            UDP -> "udp"
            TCP or UDP -> "tcpudp"
            else -> "any"
        }
        val prefix = draft.nodeId?.let { "n$it-" }.orEmpty()
        return "${prefix}in-$port-$suffix"
    }

    fun isAuto(tag: String, draft: InboundDraft): Boolean {
        if (tag.isEmpty()) return true
        val base = compose(draft)
        if (tag == base) return true
        val suffix = tag.removePrefix("$base-")
        return suffix != tag && suffix.isNotEmpty() && suffix.all(Char::isDigit)
    }

    private fun transports(draft: InboundDraft): Int {
        val protocol = draft.protocol
        if (protocol in setOf("hysteria", "wireguard", "amneziawg", "tuic")) return UDP
        if (protocol == "masque") {
            val alpn = draft[JsonPath("streamSettings.tlsSettings.alpn")].strings()
            val h2 = "h2" in alpn
            return (if (h2) TCP else 0) or (if (!h2 || "h3" in alpn) UDP else 0)
        }
        var bits = if (draft.network == "kcp" || draft.network == "quic") UDP else TCP
        val settings = draft[InboundDraft.P_SETTINGS].obj()
        if (settings != null) {
            if (protocol == "shadowsocks" || protocol == "tunnel") {
                val value = settings[if (protocol == "tunnel") "allowedNetwork" else "network"].str().orEmpty()
                if (value.isNotEmpty()) {
                    bits = 0
                    value.split(',').map { it.trim() }.forEach { if (it == "tcp") bits = bits or TCP else if (it == "udp") bits = bits or UDP }
                }
            } else if (protocol == "mixed" && settings["udp"].bool() == true) {
                bits = bits or UDP
            }
        }
        return if (bits == 0) TCP else bits
    }
}
