package dev.autorasqr.xuimanager.data.panel.v3

import kotlinx.serialization.Serializable

/** Row of GET /panel/api/clients/list (service.ClientWithAttachments). totalGB is in bytes despite the name. */
@Serializable
internal data class V3ClientRow(
    val email: String = "",
    val subId: String = "",
    val totalGB: Long = 0,
    val expiryTime: Long = 0,
    val enable: Boolean = true,
    val comment: String = "",
    val inboundIds: List<Int>? = null,
    val traffic: V3Traffic? = null,
)

@Serializable
internal data class V3Traffic(
    val up: Long = 0,
    val down: Long = 0,
)

@Serializable
internal data class V3Status(
    val cpu: Double = 0.0,
    val mem: V3Usage = V3Usage(),
    val uptime: Long = 0,
    val loads: List<Double> = emptyList(),
    val xray: V3Xray = V3Xray(),
    val panelVersion: String = "",
)

@Serializable
internal data class V3Usage(val current: Long = 0, val total: Long = 0)

@Serializable
internal data class V3Xray(val state: String = "", val version: String = "", val errorMsg: String = "")

@Serializable
internal data class V3X25519(val privateKey: String = "", val publicKey: String = "")

@Serializable
internal data class V3RealityVerdict(
    val target: String = "",
    val host: String = "",
    val feasible: Boolean = false,
    val latencyMs: Long = 0,
)

@Serializable
internal data class V3BulkResult(
    val changed: Int = 0,
    val skipped: List<V3BulkSkip> = emptyList(),
)

@Serializable
internal data class V3BulkSkip(val email: String = "", val reason: String = "")

/** NodeView from /panel/api/nodes/list. */
@Serializable
internal data class V3Node(
    val id: Int = 0,
    val name: String = "",
    val remark: String = "",
    val scheme: String = "https",
    val address: String = "",
    val port: Int = 0,
    val basePath: String = "/",
    val enable: Boolean = true,
    val status: String = "",
    val latencyMs: Long = 0,
    val cpuPct: Double = 0.0,
    val memPct: Double = 0.0,
    val uptimeSecs: Long = 0,
    val xrayState: String = "",
    val xrayVersion: String = "",
    val xrayError: String = "",
    val panelVersion: String = "",
    val lastError: String = "",
    val lastHeartbeat: Long = 0,
    val inboundCount: Int = 0,
    val clientCount: Int = 0,
    val onlineCount: Int = 0,
    val hasApiToken: Boolean = false,
    val tlsVerifyMode: String = "verify",
    val pinnedCertSha256: String = "",
    val inboundSyncMode: String = "all",
    val inboundTags: List<String>? = null,
    val outboundTag: String = "",
    val allowPrivateAddress: Boolean = false,
    val transitive: Boolean = false,
)

/** ProbeResultUI from /nodes/test and /nodes/probe. */
@Serializable
internal data class V3NodeProbe(
    val status: String = "",
    val error: String = "",
    val latencyMs: Long = 0,
    val cpuPct: Double = 0.0,
    val memPct: Double = 0.0,
    val uptimeSecs: Long = 0,
    val xrayState: String = "",
    val xrayVersion: String = "",
    val xrayError: String = "",
    val panelVersion: String = "",
)

@Serializable
internal data class V3RemoteInbound(val tag: String = "", val remark: String = "", val protocol: String = "", val port: Int = 0)

/** PanelUpdateInfo from /server/getPanelUpdateInfo. */
@Serializable
internal data class V3PanelUpdateInfo(
    val currentVersion: String = "",
    val latestVersion: String = "",
    val updateAvailable: Boolean = false,
)

/** PanelUpdateStatus from /server/getUpdateStatus; runId is a decimal string. */
@Serializable
internal data class V3PanelUpdateStatus(val runId: String = "", val state: String = "", val exitCode: Int = 0)

@Serializable
internal data class V3NodeUpdateResult(val id: Int = 0, val name: String = "", val ok: Boolean = false, val error: String = "")
