package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

data class PanelEndpoint(
    val host: String,
    val port: Int,
    val basePath: String,
) {
    fun baseUrl(): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(host)
        .port(port)
        .apply { basePath.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment) }
        .addPathSegment("")
        .build()
}

/** v3 authenticates with [apiToken]; v2 logs in with [username] and [password]. */
data class PanelCredentials(
    val apiToken: String?,
    val username: String,
    val password: String?,
) {
    override fun toString() = "PanelCredentials(username=$username, token=${apiToken?.let { "***" }}, password=${password?.let { "***" }})"
}

data class PanelCapabilities(
    /** v3 clients are first-class and may be attached to several inbounds; in v2 a client lives in one inbound. */
    val multiInboundClients: Boolean,
    /** v3 can probe Reality targets from the server (scanRealityTargets). */
    val realityScan: Boolean,
    /** v3 hands routing profiles to Happ/INCY in the subscription and exposes the Xray template over the API. */
    val routing: Boolean = false,
    /** v3 manages other 3x-ui panels as nodes (/panel/api/nodes) and can host inbounds on them. */
    val nodes: Boolean = false,
    /** v3 checks GitHub for a newer release and runs the official updater on itself and on its nodes. */
    val selfUpdate: Boolean = false,
)

/** The panel's Xray config template and the outbound test URL saved alongside it. */
data class XrayTemplate(val config: JsonObject, val outboundTestUrl: String)

data class PanelStatus(
    val cpuPercent: Double,
    val memUsed: Long,
    val memTotal: Long,
    val uptimeSeconds: Long,
    val loads: List<Double>,
    val xrayState: String,
    val xrayVersion: String,
    val xrayError: String,
    val panelVersion: String,
)

data class PanelClient(
    val email: String,
    val enabled: Boolean,
    val subId: String,
    val totalBytes: Long,
    val expiryTime: Long,
    val up: Long,
    val down: Long,
    val inboundIds: List<Int>,
    val comment: String,
)

data class PanelInbound(
    val id: Int,
    val remark: String,
    val port: Int,
    val protocol: String,
    val network: String,
    val security: String,
    val enabled: Boolean = true,
    val up: Long = 0,
    val down: Long = 0,
    val clientCount: Int = 0,
    /** Set for Reality inbounds. */
    val reality: RealityInfo? = null,
    /** The node hosting the inbound; null for the panel's own inbounds. */
    val nodeId: Int? = null,
) {
    /** XTLS Vision works only for VLESS over raw TCP with TLS or Reality. */
    val supportsVision: Boolean
        get() = protocol == "vless" && network in setOf("tcp", "raw") && security in setOf("reality", "tls")
}

/** The parts of an inbound's realitySettings the app shows and edits. */
data class RealityInfo(
    /** host:port unauthenticated traffic is forwarded to (127.0.0.1:port for self-steal). */
    val target: String,
    val serverNames: List<String>,
    val fingerprint: String,
) {
    val targetHost: String get() = target.substringBeforeLast(':').trim('[', ']')
    val targetPort: Int? get() = target.substringAfterLast(':', "").toIntOrNull()
    val isLoopback: Boolean get() = targetHost == "127.0.0.1" || targetHost == "localhost" || targetHost == "::1"
}

data class NewClient(
    val email: String,
    val subId: String,
    val totalBytes: Long,
    val expiryTime: Long,
    val flow: String,
    val inboundIds: List<Int>,
    val enabled: Boolean = true,
)

/** Desired state of a client's editable fields. */
data class ClientChanges(
    val email: String,
    val subId: String,
    val totalBytes: Long,
    val expiryTime: Long,
    val enabled: Boolean,
    val inboundIds: List<Int>,
)

data class RealityParams(
    /** host:port the server forwards unauthenticated traffic to. */
    val target: String,
    val serverName: String,
    val privateKey: String,
    val publicKey: String,
    val shortId: String,
    /** uTLS fingerprint advertised to clients in links and subscriptions. */
    val fingerprint: String = "chrome",
) {
    override fun toString() =
        "RealityParams(target=$target, serverName=$serverName, publicKey=$publicKey, shortId=$shortId, fingerprint=$fingerprint)"
}

data class InboundSpec(
    val remark: String,
    val port: Int,
    val protocol: InboundProtocol,
    val transport: InboundTransport,
    /** Required for VLESS and Trojan. */
    val reality: RealityParams?,
    /** WebSocket/XHTTP path ("/abc") or gRPC service name ("abc"); unused for TCP. */
    val path: String,
    /** Server key of a Shadowsocks 2022 inbound (base64 of 32 random bytes). */
    val shadowsocksPassword: String?,
    /** Node to host the inbound on (3x-ui v3); null for the panel itself. */
    val nodeId: Int? = null,
) {
    override fun toString() =
        "InboundSpec(remark=$remark, port=$port, protocol=$protocol, transport=$transport, reality=$reality, path=$path, nodeId=$nodeId)"
}

data class X25519Keys(val privateKey: String, val publicKey: String) {
    override fun toString() = "X25519Keys(publicKey=$publicKey, privateKey=***)"
}

data class RealityTargetVerdict(
    val target: String,
    val host: String,
    val feasible: Boolean,
    val latencyMs: Long,
)

data class SubscriptionSettings(
    val enabled: Boolean,
    val port: Int,
    val path: String,
    val domain: String,
    val certFile: String,
    val keyFile: String,
    val uri: String,
) {
    val tls: Boolean get() = certFile.isNotEmpty() && keyFile.isNotEmpty()
    val scheme: String get() = if (tls) "https" else "http"

    /** Mirrors SettingService.BuildSubURIBase in 3x-ui. */
    fun url(serverHost: String, subId: String): String {
        if (uri.isNotBlank()) return uri.trimEnd('/') + "/" + subId
        val host = domain.ifBlank { serverHost }.let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
        val portPart = if ((tls && port == 443) || (!tls && port == 80)) "" else ":$port"
        val normalizedPath = "/" + path.trim('/').ifEmpty { "sub" } + "/"
        return "$scheme://$host$portPart$normalizedPath$subId"
    }
}

/** null fields are left as they are on the panel. */
data class SubscriptionUpdate(
    val enabled: Boolean,
    val port: Int?,
    val path: String?,
    val certFile: String?,
    val keyFile: String?,
)

/** A node as the central panel reports it (NodeView). Transitive sub-nodes of a node have id 0 and are read-only. */
data class PanelNode(
    val id: Int,
    val name: String,
    val remark: String,
    val scheme: String,
    val address: String,
    val port: Int,
    val basePath: String,
    val enabled: Boolean,
    /** online, offline or unknown (no heartbeat yet). */
    val status: String,
    val latencyMs: Long,
    val cpuPercent: Double,
    val memPercent: Double,
    val uptimeSeconds: Long,
    val xrayState: String,
    val xrayVersion: String,
    val xrayError: String,
    val panelVersion: String,
    val lastError: String,
    val lastHeartbeat: Long,
    val inboundCount: Int,
    val clientCount: Int,
    val onlineCount: Int,
    val hasApiToken: Boolean,
    val tlsVerifyMode: String,
    val pinnedCertSha256: String,
    val inboundSyncMode: String,
    val inboundTags: List<String>,
    val outboundTag: String,
    val allowPrivateAddress: Boolean,
    val transitive: Boolean,
)

/**
 * Node connection details for add, update, test and the certificate/inbound lookups (NodeMutationRequest).
 * [apiToken] is write-only on the panel: null keeps the stored one on update.
 */
data class NodeMutation(
    val name: String,
    val remark: String,
    val scheme: String,
    val address: String,
    val port: Int,
    val basePath: String,
    val apiToken: String?,
    val enabled: Boolean,
    val allowPrivateAddress: Boolean,
    val tlsVerifyMode: String,
    val pinnedCertSha256: String,
    val inboundSyncMode: String,
    val inboundTags: List<String>,
    val outboundTag: String,
    /** The node being edited, so the panel can fall back to its stored token; 0 for a new one. */
    val id: Int = 0,
) {
    override fun toString() = "NodeMutation(id=$id, name=$name, $scheme://$address:$port$basePath, token=${apiToken?.let { "***" }})"
}

/** Heartbeat snapshot from /nodes/test and /nodes/probe (ProbeResultUI). */
data class NodeProbe(
    val online: Boolean,
    val error: String,
    val latencyMs: Long,
    val cpuPercent: Double,
    val memPercent: Double,
    val uptimeSeconds: Long,
    val xrayState: String,
    val xrayVersion: String,
    val xrayError: String,
    val panelVersion: String,
)

/** An inbound on a node, offered for selective sync. */
data class RemoteInboundOption(val tag: String, val remark: String, val protocol: String, val port: Int)

/** getPanelUpdateInfo: the running and the latest stable release as the panel sees them on GitHub. */
data class PanelUpdateInfo(val currentVersion: String, val latestVersion: String, val updateAvailable: Boolean)

/** getUpdateStatus: the outcome of the latest self-update; [state] is pending, success or failed. */
data class PanelUpdateRun(val runId: String, val state: String, val exitCode: Int)

/** One entry of /nodes/updatePanel: whether the node accepted the update request. */
data class NodeUpdateResult(val id: Int, val name: String, val ok: Boolean, val error: String)
