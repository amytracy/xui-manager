package dev.autorasqr.xuimanager.data.inbound.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import java.util.Base64

/**
 * Defaults and generators ported from 3x-ui v3.10.2's frontend (lib/xray/inbound-defaults.ts, inbound-tls-defaults.ts,
 * schemas/protocols, forms/transport/FinalMaskForm.tsx, utils RandomUtil, amneziawg-obfuscation.ts).
 */
object EditorDefaults {
    private val random = SecureRandom()

    /** Protocols whose inbounds can be placed on a node (node-protocols.ts). */
    val NODE_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "mtproto", "amneziawg", "tuic", "masque")

    val PROTOCOLS = listOf(
        "vmess", "vless", "trojan", "shadowsocks", "wireguard", "hysteria", "http", "mixed",
        "tunnel", "tun", "mtproto", "amneziawg", "tuic", "masque",
    )
    val NETWORKS = listOf("tcp", "kcp", "ws", "grpc", "httpupgrade", "xhttp", "xdrive")
    val NETWORK_SLICE_KEYS = NETWORKS.map { "${it}Settings" }
    val FINGERPRINTS = listOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized",
        "randomizednoalpn", "unsafe",
    )
    val SS_METHODS = listOf(
        "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
    )
    val TLS_VERSIONS = listOf("1.0", "1.1", "1.2", "1.3")
    val ALPN = listOf("h3", "h2", "http/1.1")
    val CIPHER_SUITES = listOf(
        "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )
    val DOMAIN_STRATEGIES = listOf(
        "AsIs", "UseIP", "UseIPv6v4", "UseIPv6", "UseIPv4v6", "UseIPv4",
        "ForceIP", "ForceIPv6v4", "ForceIPv6", "ForceIPv4v6", "ForceIPv4",
    )
    val TCP_MASK_TYPES = listOf("fragment", "sudoku", "header-custom", "xmc")
    val UDP_MASK_TYPES = listOf("salamander", "mkcp-legacy", "header-custom", "xdns", "xicmp", "noise", "sudoku", "realm", "udphop")
    const val MASQUE_DEFAULT_PATH = "/.well-known/masque/ip/*/*/"

    // --- generators ---

    fun randomSeq(length: Int, alphabet: String = ALNUM): String =
        buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    fun randomLowerAndNum(length: Int) = randomSeq(length, LOWER_NUM)
    fun randomHex(length: Int) = randomSeq(length, HEX)

    /** Eight short ids of lengths 2..16 in random order, as 3x-ui's RandomUtil.randomShortIds. */
    fun randomShortIds(): List<String> = listOf(2, 4, 6, 8, 10, 12, 14, 16).shuffled(random).map(::randomHex)

    fun randomSpiderX(): String = "/" + randomSeq(15)

    fun shadowsocksPassword(method: String): String {
        val bytes = ByteArray(if (method == "2022-blake3-aes-128-gcm") 16 else 32).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** WireGuard/AmneziaWG Curve25519 key pair, base64 like `wg genkey`. */
    fun wireguardKeyPair(): Pair<String, String> {
        val private = ByteArray(32).also(random::nextBytes)
        private[0] = (private[0].toInt() and 248).toByte()
        private[31] = ((private[31].toInt() and 127) or 64).toByte()
        val encoded = Base64.getEncoder().encodeToString(private)
        return encoded to wireguardPublicKey(encoded)!!
    }

    fun wireguardPublicKey(privateKey: String): String? {
        val bytes = runCatching { Base64.getDecoder().decode(privateKey.trim()) }.getOrNull() ?: return null
        if (bytes.size != 32) return null
        val public = ByteArray(32)
        X25519.scalarMultBase(bytes, 0, public, 0)
        return Base64.getEncoder().encodeToString(public)
    }

    fun mtprotoSecret(domain: String): String =
        "ee" + randomHex(32) + domain.ifBlank { "www.cloudflare.com" }.toByteArray().joinToString("") { "%02x".format(it) }

    /** AmneziaWG 3.1 obfuscation set; mirrors amneziawg.GenerateObfuscation31. */
    fun awgObfuscation(): JsonObject {
        fun r(min: Int, max: Int) = min + random.nextInt(max - min + 1)
        val jmin = r(40, 89)
        val s1 = r(15, 150)
        var s2 = r(15, 150)
        while (s1 + 56 == s2) s2 = r(15, 150)
        val band = (2147483647 - 5 + 1) / 4
        val h = (0 until 4).map { i -> val lo = 5 + i * band; (lo + random.nextInt(band)).toString() }
        val cpLo = r(8, 24)
        val rekeyLo = r(100, 120)
        val rekeyHi = rekeyLo + r(10, 40)
        val rejectLo = rekeyHi + r(30, 60)
        val rekeyTimeoutLo = r(3, 6)
        val keepaliveLo = r(8, 12)
        val attemptsLo = r(15, 25)
        return buildJsonObject {
            put("jc", r(3, 6)); put("jmin", jmin); put("jmax", jmin + r(50, 250))
            put("s1", s1); put("s2", s2); put("s3", r(12, 55)); put("s4", r(12, 27))
            put("h1", h[0]); put("h2", h[1]); put("h3", h[2]); put("h4", h[3])
            put("i1", "<r ${r(32, 256)}>"); put("i2", ""); put("i3", ""); put("i4", ""); put("i5", "")
            put("headerProtectionKey", Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes)))
            put("contentPaddingAddition", "$cpLo-${cpLo + r(8, 40)}")
            put("rekeyAfterTime", "$rekeyLo-$rekeyHi")
            put("rekeyTimeout", "$rekeyTimeoutLo-${rekeyTimeoutLo + r(1, 4)}")
            put("rejectAfterTime", "$rejectLo-${rejectLo + r(30, 90)}")
            put("keepaliveTimeout", "$keepaliveLo-${keepaliveLo + r(2, 8)}")
            put("maxHandshakeAttempts", "$attemptsLo-${attemptsLo + r(10, 25)}")
            put("randomTrailers", false)
            put("disableCookies", false)
        }
    }

    // --- protocol settings ---

    /** Settings for a new inbound of [protocol] (createDefaultInboundSettings), with fresh secrets. */
    fun protocolSettings(protocol: String): JsonObject = when (protocol) {
        "vless" -> buildJsonObject { putJsonArray("clients") {}; put("decryption", "none"); put("encryption", "none"); putJsonArray("fallbacks") {} }
        "vmess" -> buildJsonObject { putJsonArray("clients") {} }
        "trojan" -> buildJsonObject { putJsonArray("clients") {}; putJsonArray("fallbacks") {} }
        "shadowsocks" -> buildJsonObject {
            put("method", "2022-blake3-aes-256-gcm"); put("password", shadowsocksPassword("2022-blake3-aes-256-gcm"))
            put("network", "tcp,udp"); putJsonArray("clients") {}; put("ivCheck", false)
        }
        "hysteria" -> buildJsonObject { put("version", 2); putJsonArray("clients") {} }
        "http" -> buildJsonObject { put("accounts", accounts()); put("allowTransparent", false) }
        "mixed" -> buildJsonObject { put("auth", "password"); put("accounts", accounts()); put("udp", false); put("ip", "127.0.0.1") }
        "tunnel" -> buildJsonObject { putJsonObject("portMap") {}; put("allowedNetwork", "tcp,udp"); put("followRedirect", false) }
        "tun" -> buildJsonObject {
            put("name", "xray0"); put("mtu", 1500); putJsonArray("gateway") {}; putJsonArray("dns") {}
            put("userLevel", 0); putJsonArray("autoSystemRoutingTable") {}; put("autoOutboundsInterface", "auto")
        }
        "wireguard" -> buildJsonObject {
            put("mtu", 1420); put("secretKey", wireguardKeyPair().first); putJsonArray("peers") {}; putJsonArray("clients") {}
            put("noKernelTun", false); put("subnetIp", "10.0.0.0"); put("subnetCidr", 24)
        }
        "mtproto" -> buildJsonObject { put("fakeTlsDomain", "www.cloudflare.com"); putJsonArray("clients") {} }
        "amneziawg" -> {
            val (privateKey, publicKey) = wireguardKeyPair()
            buildJsonObject {
                put("server", JsonObject(
                    buildJsonObject {
                        put("privateKey", privateKey); put("publicKey", publicKey)
                        put("subnetIp", "10.8.1.0"); put("subnetCidr", 24)
                        put("primaryDns", "8.8.8.8"); put("secondaryDns", "8.8.4.4")
                        put("externalInterface", ""); put("ipv6Enabled", false); put("ipv6Subnet", ""); put("ipv6ExternalInterface", "")
                    } + awgObfuscation(),
                ))
                putJsonArray("clients") {}
            }
        }
        "tuic" -> buildJsonObject { put("server", TUIC_SERVER); putJsonArray("clients") {} }
        "masque" -> buildJsonObject { putJsonArray("clients") {}; put("address", jsonStrings(MASQUE_ADDRESS_POOL)) }
        else -> JsonObject(emptyMap())
    }

    /** Schema defaults without random parts, filled into an edited inbound so every field shows its value. */
    fun settingsSchemaDefaults(protocol: String): JsonObject = when (protocol) {
        "vless" -> buildJsonObject { put("decryption", "none"); put("encryption", "none") }
        "shadowsocks" -> buildJsonObject { put("method", "2022-blake3-aes-256-gcm"); put("network", "tcp,udp"); put("ivCheck", false) }
        "http" -> buildJsonObject { put("allowTransparent", false) }
        "mixed" -> buildJsonObject { put("auth", "password"); put("udp", false); put("ip", "127.0.0.1") }
        "tunnel" -> buildJsonObject { putJsonObject("portMap") {}; put("allowedNetwork", "tcp,udp"); put("followRedirect", false) }
        "tun" -> buildJsonObject { put("name", "xray0"); put("mtu", 1500); put("userLevel", 0); put("autoOutboundsInterface", "auto") }
        "wireguard" -> buildJsonObject { put("noKernelTun", false); put("subnetIp", "") }
        "mtproto" -> buildJsonObject { put("fakeTlsDomain", "www.cloudflare.com") }
        "tuic" -> buildJsonObject { put("server", TUIC_SERVER) }
        "masque" -> buildJsonObject { put("address", jsonStrings(MASQUE_ADDRESS_POOL)) }
        "amneziawg" -> buildJsonObject {
            putJsonObject("server") {
                put("subnetIp", "10.8.1.0"); put("subnetCidr", 24); put("primaryDns", "8.8.8.8"); put("secondaryDns", "8.8.4.4")
                put("externalInterface", ""); put("ipv6Enabled", false); put("ipv6Subnet", ""); put("ipv6ExternalInterface", "")
                put("jc", 5); put("jmin", 10); put("jmax", 50); put("s1", 30); put("s2", 45); put("s3", 10); put("s4", 5)
                put("randomTrailers", false); put("disableCookies", false)
            }
        }
        else -> JsonObject(emptyMap())
    }

    private fun accounts(): JsonArray = buildJsonArray {
        add(buildJsonObject { put("user", randomLowerAndNum(8)); put("pass", randomLowerAndNum(12)) })
    }

    private val MASQUE_ADDRESS_POOL = listOf("10.14.0.1/24", "fd14::1/64")
    private val TUIC_SERVER = buildJsonObject {
        put("certificate", ""); put("private_key", ""); put("congestion_control", "bbr")
        put("alpn", jsonStrings(listOf("h3", "spdy/3.1"))); put("udp_relay_mode", "native")
        put("zero_rtt_handshake", true); put("log_level", "info"); put("max_idle_time", 15)
        put("authentication_timeout", 3); put("max_udp_relay_packet_size", 1500); put("sni", "")
    }

    // --- stream settings ---

    /** Per-network settings with schema defaults (InboundFormModal newStreamSlice). */
    fun streamSlice(network: String): JsonObject = when (network) {
        "tcp" -> buildJsonObject { put("acceptProxyProtocol", false); putJsonObject("header") { put("type", "none") } }
        "kcp" -> buildJsonObject {
            put("mtu", 1350); put("tti", 20); put("uplinkCapacity", 5); put("downlinkCapacity", 20)
            put("cwndMultiplier", 1); put("maxSendingWindow", 2097152)
        }
        "ws" -> buildJsonObject { put("acceptProxyProtocol", false); put("path", "/"); put("host", ""); putJsonObject("headers") {}; put("heartbeatPeriod", 0) }
        "grpc" -> buildJsonObject { put("serviceName", ""); put("authority", ""); put("multiMode", false) }
        "httpupgrade" -> buildJsonObject { put("acceptProxyProtocol", false); put("path", "/"); put("host", ""); putJsonObject("headers") {} }
        "xhttp" -> XHTTP_DEFAULTS
        "xdrive" -> buildJsonObject { put("service", "Google Drive"); put("remoteFolder", ""); putJsonArray("secrets") {} }
        "hysteria" -> buildJsonObject { put("version", 2); put("auth", ""); put("udpIdleTimeout", 60) }
        else -> JsonObject(emptyMap())
    }

    private val XHTTP_DEFAULTS = buildJsonObject {
        put("path", "/"); put("host", ""); put("mode", "auto"); put("xPaddingBytes", "100-1000")
        put("xPaddingObfsMode", false); put("xPaddingKey", ""); put("xPaddingHeader", ""); put("xPaddingPlacement", ""); put("xPaddingMethod", "")
        put("sessionIDPlacement", ""); put("sessionIDKey", ""); put("sessionIDTable", ""); put("sessionIDLength", "")
        put("seqPlacement", ""); put("seqKey", ""); put("uplinkDataPlacement", ""); put("uplinkDataKey", "")
        put("scMaxEachPostBytes", ""); put("noSSEHeader", false); put("scMaxBufferedPosts", 30); put("scStreamUpServerSecs", "20-80")
        put("serverMaxHeaderBytes", 0); put("uplinkHTTPMethod", ""); putJsonObject("headers") {}; put("scMinPostsIntervalMs", "")
        put("uplinkChunkSize", 0); put("noGRPCHeader", false); put("enableXmux", false)
    }

    /** XMUX_FRESH_DEFAULTS: what turning XMUX on starts with. */
    val XMUX_FRESH = buildJsonObject {
        put("maxConcurrency", ""); put("maxConnections", 3); put("cMaxReuseTimes", 0)
        put("hMaxRequestTimes", "600-900"); put("hMaxReusableSecs", "1800-3000"); put("hKeepAlivePeriod", 0)
    }

    val TCP_HTTP_HEADER = buildJsonObject {
        put("type", "http")
        putJsonObject("request") { put("version", "1.1"); put("method", "GET"); put("path", jsonStrings(listOf("/"))); putJsonObject("headers") {} }
        putJsonObject("response") { put("version", "1.1"); put("status", "200"); put("reason", "OK"); putJsonObject("headers") {} }
    }

    val HYSTERIA_MASQUERADE = buildJsonObject {
        put("type", ""); put("dir", ""); put("url", ""); put("rewriteHost", false); put("insecure", false)
        put("content", ""); putJsonObject("headers") {}; put("statusCode", 0)
    }

    val SOCKOPT = buildJsonObject {
        put("acceptProxyProtocol", false); put("tcpFastOpen", false); put("mark", 0); put("tproxy", "off"); put("tcpMptcp", false)
        put("penetrate", false); put("domainStrategy", "AsIs"); put("tcpMaxSeg", 0); put("dialerProxy", "")
        put("tcpKeepAliveInterval", 0); put("tcpKeepAliveIdle", 0); put("tcpUserTimeout", 0); put("tcpcongestion", "bbr")
        put("V6Only", false); put("tcpWindowClamp", 0); put("interface", ""); putJsonArray("trustedXForwardedFor") {}
        put("addressPortStrategy", "none"); putJsonArray("customSockopt") {}
    }

    val CUSTOM_SOCKOPT = buildJsonObject { put("type", "int"); put("level", "6"); put("opt", ""); put("value", "") }

    val SNIFFING = buildJsonObject {
        put("enabled", false); put("destOverride", jsonStrings(listOf("http", "tls", "quic", "fakedns")))
        put("metadataOnly", false); put("routeOnly", false); putJsonArray("ipsExcluded") {}; putJsonArray("domainsExcluded") {}
    }

    // --- security ---

    fun tlsCertificate(): JsonObject = buildJsonObject {
        put("useFile", true); put("certificateFile", ""); put("keyFile", ""); putJsonArray("certificate") {}; putJsonArray("key") {}
        put("ocspStapling", 0); put("oneTimeLoading", false); put("usage", "encipherment"); put("buildChain", false)
    }

    private val TLS_SCHEMA = buildJsonObject {
        put("serverName", ""); put("minVersion", "1.2"); put("maxVersion", "1.3"); put("cipherSuites", "")
        put("rejectUnknownSni", false); put("disableSystemRoot", false); put("enableSessionResumption", false)
        putJsonArray("certificates") {}; put("alpn", jsonStrings(listOf("h2", "http/1.1"))); put("echServerKeys", "")
        putJsonObject("settings") { put("fingerprint", ""); put("echConfigList", ""); putJsonArray("pinnedPeerCertSha256") {}; put("verifyPeerCertByName", "") }
    }

    fun tlsSchemaDefaults(): JsonObject = TLS_SCHEMA

    /** createTlsSettingsWithDefaultCert: one empty file certificate, uTLS chrome, HTTP/1.1 only for WebSocket. */
    fun tlsSettings(network: String?): JsonObject {
        var tls = TLS_SCHEMA.with(JsonPath("certificates"), JsonArray(listOf(tlsCertificate())))
            .with(JsonPath("settings.fingerprint"), json("chrome"))
        if (network == "ws") tls = tls.with(JsonPath("alpn"), jsonStrings(listOf("http/1.1")))
        return tls
    }

    /** Hysteria and MASQUE: QUIC-ready TLS (ALPN h3, no uTLS). */
    fun quicTlsSettings(): JsonObject = tlsSettings(null)
        .with(JsonPath("alpn"), jsonStrings(listOf("h3")))
        .with(JsonPath("settings.fingerprint"), json(""))

    private val REALITY_SCHEMA = buildJsonObject {
        put("show", false); put("xver", 0); put("target", ""); putJsonArray("serverNames") {}; put("privateKey", "")
        put("minClientVer", ""); put("maxClientVer", ""); put("maxTimediff", 0); putJsonArray("shortIds") {}; put("mldsa65Seed", "")
        putJsonObject("settings") { put("publicKey", ""); put("fingerprint", "chrome"); put("serverName", ""); put("spiderX", "/"); put("mldsa65Verify", "") }
    }

    fun realitySchemaDefaults(): JsonObject = REALITY_SCHEMA

    /** onSecurityChange('reality'): schema defaults, fresh short ids and spiderX; the key pair comes from the panel. */
    fun realitySettings(): JsonObject = REALITY_SCHEMA
        .with(JsonPath("shortIds"), jsonStrings(randomShortIds()))
        .with(JsonPath("settings.spiderX"), json(randomSpiderX()))

    val REALITY_LIMIT = buildJsonObject { put("afterBytes", 0); put("bytesPerSec", 0); put("burstBytesPerSec", 0) }

    val ECH_SOCKOPT = buildJsonObject { put("dialerProxy", ""); put("domainStrategy", "AsIs"); put("tcpFastOpen", false); put("tcpMptcp", false) }

    // --- finalmask ---

    fun tcpMaskSettings(type: String): JsonObject = when (type) {
        "fragment" -> buildJsonObject { put("packets", "1-3"); put("lengths", jsonStrings(listOf("100-200"))); putJsonArray("delays") {}; put("maxSplit", "") }
        "sudoku" -> SUDOKU
        "header-custom" -> buildJsonObject { putJsonArray("clients") {}; putJsonArray("servers") {} }
        "xmc" -> buildJsonObject { put("hostname", ""); put("profiles", JsonArray(listOf(XMC_PROFILE))); put("password", randomLowerAndNum(16)) }
        else -> JsonObject(emptyMap())
    }

    fun udpMaskSettings(type: String): JsonObject = when (type) {
        "salamander" -> buildJsonObject { put("password", randomLowerAndNum(16)) }
        "mkcp-legacy" -> buildJsonObject { put("header", ""); put("value", "") }
        "xdns" -> buildJsonObject { putJsonArray("domains") {} }
        "xicmp" -> buildJsonObject { put("dgram", false); putJsonArray("ips") {} }
        "realm" -> buildJsonObject { put("url", ""); putJsonArray("stunServers") {} }
        "header-custom" -> buildJsonObject { putJsonArray("client") {}; putJsonArray("server") {} }
        "noise" -> buildJsonObject { put("reset", 0); putJsonArray("noise") {} }
        "sudoku" -> SUDOKU
        "udphop" -> buildJsonObject { put("mode", "intervalRemote"); put("interval", "5-10"); put("remotePorts", "") }
        else -> JsonObject(emptyMap())
    }

    val XMC_PROFILE = buildJsonObject { put("username", ""); put("uuid", ""); put("texturesValue", ""); put("texturesSignature", "") }
    val NOISE_ITEM = buildJsonObject { put("rand", "1-8192"); put("randRange", "0-255"); put("type", "array"); putJsonArray("packet") {}; put("delay", "10-20") }
    private val SUDOKU = buildJsonObject {
        put("password", ""); put("ascii", ""); put("customTable", ""); putJsonArray("customTables") {}; put("paddingMin", 0); put("paddingMax", 0)
    }

    val QUIC_PARAMS = buildJsonObject {
        put("congestion", "bbr"); put("debug", false); put("maxIdleTimeout", 30); put("keepAlivePeriod", 10)
        put("disablePathMTUDiscovery", false); put("maxIncomingStreams", 1024)
        put("initStreamReceiveWindow", 8388608); put("maxStreamReceiveWindow", 8388608)
        put("initConnectionReceiveWindow", 20971520); put("maxConnectionReceiveWindow", 20971520)
    }

    val UDP_HOP = buildJsonObject { put("ports", "20000-50000"); put("interval", "5-10") }

    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val LOWER_NUM = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val HEX = "0123456789abcdef"
}
