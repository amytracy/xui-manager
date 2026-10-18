package dev.autorasqr.xuimanager.data.inbound.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Port of 3x-ui's lib/xray/protocol-capabilities.ts plus the tab visibility rules of InboundFormModal. */
object InboundCapabilities {
    private val TLS_PROTOCOLS = setOf("vmess", "vless", "trojan", "shadowsocks")
    private val TLS_NETWORKS = setOf("tcp", "ws", "http", "grpc", "httpupgrade", "xhttp")
    private val REALITY_PROTOCOLS = setOf("vless", "trojan")
    private val REALITY_NETWORKS = setOf("tcp", "http", "grpc", "xhttp")
    private val STREAM_PROTOCOLS = setOf("vmess", "vless", "trojan", "shadowsocks", "hysteria", "wireguard", "tunnel", "masque")
    private val PROTOCOL_TAB = setOf("vless", "shadowsocks", "http", "mixed", "tunnel", "tun", "wireguard", "mtproto", "amneziawg", "tuic", "masque")

    fun canEnableTls(d: InboundDraft): Boolean =
        d.protocol == "hysteria" || d.protocol == "masque" || (d.protocol in TLS_PROTOCOLS && d.network in TLS_NETWORKS)

    fun canEnableReality(d: InboundDraft): Boolean = d.protocol in REALITY_PROTOCOLS && d.network in REALITY_NETWORKS

    /** VLESS encryption is on when encryption/decryption hold a generated value instead of "none". */
    fun hasVlessEncryption(d: InboundDraft): Boolean {
        fun set(v: String?) = !v.isNullOrEmpty() && v != "none"
        return set(d[JsonPath("settings.encryption")].str()) || set(d[JsonPath("settings.decryption")].str())
    }

    fun canEnableTlsFlow(d: InboundDraft): Boolean =
        d.protocol == "vless" && ((d.network == "tcp" && (d.security == "tls" || d.security == "reality")) || hasVlessEncryption(d))

    fun canEnableStream(d: InboundDraft): Boolean = d.protocol in STREAM_PROTOCOLS
    fun canEnableSniffing(d: InboundDraft): Boolean = d.protocol !in setOf("mtproto", "amneziawg", "tuic")
    fun isSS2022(d: InboundDraft): Boolean = d.protocol == "shadowsocks" && d[JsonPath("settings.method")].str().orEmpty().startsWith("2022")

    fun hasSelectableTransport(d: InboundDraft): Boolean = d.protocol !in setOf("hysteria", "masque", "wireguard", "tunnel", "tuic")
    fun tlsOnly(d: InboundDraft): Boolean = d.protocol == "hysteria" || d.protocol == "masque"

    fun isFallbackHost(d: InboundDraft): Boolean =
        (d.protocol == "vless" || d.protocol == "trojan") && d.network == "tcp" && (d.security == "tls" || d.security == "reality")

    fun showProtocolTab(d: InboundDraft): Boolean = d.protocol in PROTOCOL_TAB || isFallbackHost(d)
    fun showSecurityTab(d: InboundDraft): Boolean = canEnableStream(d) && d.protocol != "wireguard" && d.protocol != "tunnel"
}

/** formValuesToWirePayload + stream-wire-normalize.ts: what is sent to panel/api/inbounds/add|update. */
object InboundWire {
    private val TOP_LEVEL = listOf(
        "up", "down", "total", "remark", "enable", "expiryTime", "trafficReset", "trafficResetDay", "lastTrafficResetTime",
        "listen", "port", "protocol", "tag", "shareAddrStrategy", "shareAddr", "subSortIndex", "excludeFromSub", "disableFlow", "nodeId",
    )
    private val PLACEMENT_FIELDS = listOf(
        "sessionIDPlacement", "sessionIDKey", "sessionIDTable", "sessionIDLength", "seqPlacement", "seqKey",
        "uplinkDataPlacement", "uplinkDataKey", "uplinkHTTPMethod", "xPaddingPlacement", "xPaddingMethod", "xPaddingKey", "xPaddingHeader",
    )
    private val PACKET_UP_FIELDS = listOf("scMaxEachPostBytes", "scMinPostsIntervalMs", "scMaxBufferedPosts")
    private val STREAM_UP_SERVER_FIELDS = listOf("scStreamUpServerSecs")

    fun payload(draft: InboundDraft): JsonObject {
        val src = draft.inbound
        val out = linkedMapOf<String, JsonElement>()
        for (key in TOP_LEVEL) src[key]?.let { out[key] = it }
        var settings = src.at(InboundDraft.P_SETTINGS).obj() ?: JsonObject(emptyMap())
        // A saved inbound sends no clients: the panel keeps the stored ones.
        settings = if (draft.isNew) settings else settings.with(JsonPath("clients"), null)
        if ((settings["fallbacks"] as? JsonArray)?.isEmpty() == true) settings = settings.with(JsonPath("fallbacks"), null)
        out["settings"] = settings
        src.at(InboundDraft.P_STREAM).obj()?.let { out["streamSettings"] = normalizeStream(it) }
        out["sniffing"] = if (InboundCapabilities.canEnableSniffing(draft)) normalizeSniffing(src.at(InboundDraft.P_SNIFFING).obj()) else JsonPrimitive("")
        return JsonObject(out)
    }

    fun normalizeSniffing(s: JsonObject?): JsonObject {
        if (s == null || s["enabled"].bool() != true) return JsonObject(mapOf("enabled" to json(false)))
        val out = linkedMapOf<String, JsonElement>("enabled" to json(true), "destOverride" to (s["destOverride"] ?: JsonArray(emptyList())))
        if (s["metadataOnly"].bool() == true) out["metadataOnly"] = json(true)
        if (s["routeOnly"].bool() == true) out["routeOnly"] = json(true)
        if (s["ipsExcluded"].strings().isNotEmpty()) out["ipsExcluded"] = s["ipsExcluded"]!!
        if (s["domainsExcluded"].strings().isNotEmpty()) out["domainsExcluded"] = s["domainsExcluded"]!!
        return JsonObject(out)
    }

    fun normalizeStream(stream: JsonObject): JsonObject {
        val out = stream.toMutableMap()
        out["xhttpSettings"].obj()?.let { out["xhttpSettings"] = normalizeXhttp(it) }
        out["tlsSettings"].obj()?.let { out["tlsSettings"] = normalizeTls(it) }
        out["sockopt"].obj()?.let { sock -> normalizeSockopt(sock)?.let { out["sockopt"] = it } ?: out.remove("sockopt") }
        out["finalmask"].obj()?.let { fm ->
            val tcp = fm["tcp"] as? JsonArray
            val udp = fm["udp"] as? JsonArray
            val hasTcp = !tcp.isNullOrEmpty()
            val hasUdp = !udp.isNullOrEmpty()
            if (!hasTcp && !hasUdp && fm["quicParams"] == null) {
                out.remove("finalmask")
            } else {
                val m = fm.toMutableMap()
                if (!hasTcp) m.remove("tcp")
                if (!hasUdp) m.remove("udp")
                out["finalmask"] = JsonObject(m)
            }
        }
        out["hysteriaSettings"].obj()?.let { hs -> if (hs["auth"].str().isNullOrEmpty()) out["hysteriaSettings"] = hs.with(JsonPath("auth"), null) }
        return JsonObject(out)
    }

    private fun normalizeTls(tls: JsonObject): JsonObject {
        val out = tls.toMutableMap()
        if (out["fingerprint"].str() == "") out.remove("fingerprint")
        if ((out["curvePreferences"] as? JsonArray)?.isEmpty() == true) out.remove("curvePreferences")
        if (out["masterKeyLog"].str().isNullOrEmpty()) out.remove("masterKeyLog")
        out["echSockopt"].obj()?.let { ech -> normalizeSockopt(ech)?.let { out["echSockopt"] = it } ?: out.remove("echSockopt") }
        out["settings"].obj()?.let { s -> if (s["fingerprint"].str() == "") out["settings"] = s.with(JsonPath("fingerprint"), null) }
        (out["certificates"] as? JsonArray)?.let { certs -> out["certificates"] = JsonArray(certs.map(::stripUseFile)) }
        return JsonObject(out)
    }

    /** The form's useFile switch decides which half of a certificate is sent. */
    private fun stripUseFile(cert: JsonElement): JsonElement {
        val obj = cert.obj() ?: return cert
        val useFile = obj["useFile"].bool() ?: (!obj["certificateFile"].str().isNullOrEmpty() || obj["certificate"].strings().isEmpty())
        val m = obj.toMutableMap()
        m.remove("useFile")
        if (useFile) {
            m.remove("certificate"); m.remove("key")
        } else {
            m.remove("certificateFile"); m.remove("keyFile")
        }
        return JsonObject(m)
    }

    fun normalizeXhttp(raw: JsonObject): JsonObject {
        val out = raw.toMutableMap()
        val mode = out["mode"].str()?.takeIf { it.isNotEmpty() } ?: "auto"
        val enableXmux = out["enableXmux"].bool() == true
        out.remove("enableXmux")
        if (!enableXmux) out.remove("xmux")
        if (out["scMinPostsIntervalMs"].str().let { it == "" || it == "30" }) out.remove("scMinPostsIntervalMs")
        out.remove("uplinkChunkSize")
        out["xmux"].obj()?.let { xmux ->
            // xray-core rejects both maxConnections and maxConcurrency; a positive maxConnections wins.
            if (rangeUpper(xmux["maxConnections"]) > 0 && rangeUpper(xmux["maxConcurrency"]) > 0) {
                out["xmux"] = xmux.with(JsonPath("maxConcurrency"), null)
            }
        }
        dropEmpty(out, PLACEMENT_FIELDS + listOf("scMaxEachPostBytes", "scMinPostsIntervalMs", "scStreamUpServerSecs"))
        if ((out["headers"] as? JsonObject).isNullOrEmpty()) out.remove("headers")
        if (out["xPaddingObfsMode"].bool() != true) {
            out.remove("xPaddingObfsMode")
            dropEmpty(out, listOf("xPaddingKey", "xPaddingHeader", "xPaddingPlacement", "xPaddingMethod"))
        }
        if (out["noGRPCHeader"].bool() != true) out.remove("noGRPCHeader")
        if (out["noSSEHeader"].bool() != true) out.remove("noSSEHeader")
        if (out["serverMaxHeaderBytes"].int() == 0) out.remove("serverMaxHeaderBytes")
        when (mode) {
            "stream-one" -> (PACKET_UP_FIELDS + STREAM_UP_SERVER_FIELDS).forEach(out::remove)
            "stream-up" -> PACKET_UP_FIELDS.forEach(out::remove)
            "packet-up" -> out.remove("scStreamUpServerSecs")
        }
        return JsonObject(out)
    }

    fun normalizeSockopt(raw: JsonObject): JsonObject? {
        val out = raw.toMutableMap()
        listOf("tcpWindowClamp", "tcpMaxSeg", "tcpUserTimeout", "tcpKeepAliveIdle", "tcpKeepAliveInterval", "mark")
            .forEach { if (out[it].int() == 0) out.remove(it) }
        listOf("acceptProxyProtocol", "tcpFastOpen", "tcpMptcp", "penetrate", "V6Only").forEach { if (out[it].bool() == false) out.remove(it) }
        if (out["tproxy"].str() == "off") out.remove("tproxy")
        if (out["domainStrategy"].str() == "AsIs") out.remove("domainStrategy")
        if (out["addressPortStrategy"].str() == "none") out.remove("addressPortStrategy")
        dropEmpty(out, listOf("dialerProxy", "interface", "tcpcongestion"))
        if ((out["trustedXForwardedFor"] as? JsonArray)?.isEmpty() == true) out.remove("trustedXForwardedFor")
        if ((out["customSockopt"] as? JsonArray)?.isEmpty() == true) out.remove("customSockopt")
        out["happyEyeballs"].obj()?.let { he ->
            val m = he.toMutableMap()
            if (m["prioritizeIPv6"].bool() == false) m.remove("prioritizeIPv6")
            if (m["interleave"].int() == 1) m.remove("interleave")
            if (m["maxConcurrentTry"].int() == 4) m.remove("maxConcurrentTry")
            if (m.isEmpty()) out.remove("happyEyeballs") else out["happyEyeballs"] = JsonObject(m)
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    /** Upper bound of an Int32Range value: "16-32" -> 32, "4" -> 4, "" -> 0. */
    fun rangeUpper(value: JsonElement?): Long {
        val text = value.str()?.trim().orEmpty()
        if (text.isEmpty()) return 0
        return text.substringAfterLast('-').toLongOrNull() ?: 0
    }

    private fun dropEmpty(map: MutableMap<String, JsonElement>, keys: List<String>) {
        keys.forEach { key -> if (map[key].let { it == null || it.str() == "" }) map.remove(key) }
    }
}

enum class EditorTab { BASIC, PROTOCOL, STREAM, SECURITY, SNIFFING, ADVANCED }

/** A problem that blocks saving, with the tab to open and a message key for the UI. */
data class EditorIssue(val tab: EditorTab, val code: String, val detail: String = "")

/** The zod rules of InboundFormSchema that matter before the panel sees the inbound. */
object InboundValidator {
    private val adTag = Regex("^[0-9a-fA-F]{32}$")

    fun validate(d: InboundDraft): EditorIssue? {
        val listen = d[JsonPath("listen")].str().orEmpty()
        val uds = listen.startsWith("/") || listen.startsWith("@")
        val port = d[JsonPath("port")].int()
        if (port == null || port !in (if (uds) 0 else 1)..65535) return EditorIssue(EditorTab.BASIC, "port")
        if (d[JsonPath("trafficReset")].str() == "monthly" && d[JsonPath("trafficResetDay")].int() !in 1..31) {
            return EditorIssue(EditorTab.BASIC, "trafficResetDay")
        }
        when (d.protocol) {
            "vless" -> if (d[JsonPath("settings.decryption")].str().isNullOrEmpty()) return EditorIssue(EditorTab.PROTOCOL, "required", "decryption")
            "shadowsocks" -> if (InboundCapabilities.isSS2022(d) && d[JsonPath("settings.password")].str().isNullOrBlank()) {
                return EditorIssue(EditorTab.PROTOCOL, "required", "password")
            }
            "wireguard" -> if (d[JsonPath("settings.secretKey")].str().isNullOrBlank()) return EditorIssue(EditorTab.PROTOCOL, "required", "secretKey")
            "http", "mixed" -> {
                val accounts = d[JsonPath("settings.accounts")] as? JsonArray ?: JsonArray(emptyList())
                val bad = accounts.any { it.obj()?.get("user").str().isNullOrEmpty() || it.obj()?.get("pass").str().isNullOrEmpty() }
                if (bad) return EditorIssue(EditorTab.PROTOCOL, "accounts")
            }
        }
        if (d.security == "tls" && InboundCapabilities.showSecurityTab(d)) {
            val certs = (d[JsonPath("streamSettings.tlsSettings.certificates")] as? JsonArray).orEmpty().mapNotNull { it.obj() }
            if (certs.none { it["usage"].str() != "verify" }) return EditorIssue(EditorTab.SECURITY, "tlsServerCert")
            certs.forEachIndexed { index, cert ->
                val useFile = cert["useFile"].bool() ?: true
                val hasCert = if (useFile) !cert["certificateFile"].str().isNullOrBlank() else cert["certificate"].strings().any { it.isNotBlank() }
                val hasKey = if (useFile) !cert["keyFile"].str().isNullOrBlank() else cert["key"].strings().any { it.isNotBlank() }
                if (!hasCert) return EditorIssue(EditorTab.SECURITY, "tlsCert", "${index + 1}")
                if (cert["usage"].str() != "verify" && !hasKey) return EditorIssue(EditorTab.SECURITY, "tlsKey", "${index + 1}")
            }
        }
        if (d.security == "reality" && InboundCapabilities.showSecurityTab(d)) {
            val reality = JsonPath("streamSettings.realitySettings")
            val target = d[reality + "target"].str().orEmpty()
            if (!validRealityTarget(target)) return EditorIssue(EditorTab.SECURITY, "realityTarget")
            if (d[reality + "serverNames"].strings().none { it.isNotBlank() }) return EditorIssue(EditorTab.SECURITY, "required", "serverNames")
            if (d[reality + "privateKey"].str().isNullOrBlank()) return EditorIssue(EditorTab.SECURITY, "required", "privateKey")
            val min = d[reality + "minClientVer"].str().orEmpty()
            val max = d[reality + "maxClientVer"].str().orEmpty()
            if (min.isNotEmpty() && clientVer(min) == null) return EditorIssue(EditorTab.SECURITY, "clientVer", "minClientVer")
            if (max.isNotEmpty() && clientVer(max) == null) return EditorIssue(EditorTab.SECURITY, "clientVer", "maxClientVer")
            val minV = clientVer(min)
            val maxV = clientVer(max)
            if (minV != null && maxV != null && compareVer(maxV, minV) < 0) return EditorIssue(EditorTab.SECURITY, "clientVer", "maxClientVer")
        }
        if (d.protocol == "mtproto") {
            val tag = d[JsonPath("settings.adTag")].str()
            if (!tag.isNullOrEmpty() && !adTag.matches(tag)) return EditorIssue(EditorTab.PROTOCOL, "adTag")
        }
        return null
    }

    /** One to three dot-separated parts, each 0-255, padded to three (parseRealityClientVer). */
    fun clientVer(value: String): List<Int>? {
        val parts = value.trim().split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { p -> p.toIntOrNull()?.takeIf { p.all(Char::isDigit) && it in 0..255 } ?: return null }
        return numbers + List(3 - numbers.size) { 0 }
    }

    private fun compareVer(a: List<Int>, b: List<Int>): Int = a.zip(b).firstOrNull { it.first != it.second }?.let { it.first - it.second } ?: 0

    /** host:port, a bare port, or a unix socket path, like validateRealityTarget. */
    fun validRealityTarget(target: String): Boolean {
        val t = target.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("/") || t.startsWith("@")) return true
        if (t.all(Char::isDigit)) return t.toIntOrNull() in 1..65535
        val port = t.substringAfterLast(':', "")
        val host = t.substringBeforeLast(':', "").trim('[', ']')
        return host.isNotEmpty() && port.toIntOrNull() in 1..65535
    }
}

private fun JsonArray?.isNullOrEmpty() = this == null || this.isEmpty()
private fun JsonObject?.isNullOrEmpty() = this == null || this.isEmpty()
