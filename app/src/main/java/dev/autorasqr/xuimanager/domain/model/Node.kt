package dev.autorasqr.xuimanager.domain.model

enum class NodeStatus {
    ONLINE, OFFLINE, UNKNOWN;

    companion object {
        fun of(value: String): NodeStatus = when (value.lowercase()) {
            "online" -> ONLINE
            "offline" -> OFFLINE
            else -> UNKNOWN
        }
    }
}

/** A 3x-ui panel the server's panel manages as a node. */
data class Node(
    val id: Int,
    val name: String,
    val remark: String,
    val scheme: String,
    val address: String,
    val port: Int,
    val basePath: String,
    val enabled: Boolean,
    val status: NodeStatus,
    val latencyMs: Long,
    val cpuPercent: Double,
    val memPercent: Double,
    val uptimeSeconds: Long,
    val xrayState: String,
    val xrayVersion: String,
    val xrayError: String,
    val panelVersion: String,
    val lastError: String,
    val inboundCount: Int,
    val clientCount: Int,
    val onlineCount: Int,
    val hasApiToken: Boolean,
    /** verify, pin, skip or mtls. */
    val tlsVerifyMode: String,
    val pinnedCertSha256: String,
    /** all or selected. */
    val inboundSyncMode: String,
    val inboundTags: List<String>,
    val outboundTag: String,
    val allowPrivateAddress: Boolean,
    /** A sub-node of one of the nodes, shown read-only. */
    val transitive: Boolean,
    /** The app's own record when the app deployed this node over SSH. */
    val serverId: Long? = null,
) {
    val url: String get() = "$scheme://$address:$port$basePath"
}

/** A server the app set up (or is setting up) as a node that is not registered on the master panel yet. */
data class PendingNode(val serverId: Long, val name: String, val host: String, val deployed: Boolean)

data class NodesSnapshot(val nodes: List<Node>, val pending: List<PendingNode>)

/** Form input for adding or editing a node; on edit a blank [apiToken] keeps the stored one. */
data class NodeDraft(
    val id: Int = 0,
    val name: String,
    val remark: String = "",
    val scheme: String = "https",
    val address: String,
    val port: Int,
    val basePath: String = "/",
    val apiToken: String = "",
    val enabled: Boolean = true,
    val allowPrivateAddress: Boolean = false,
    val tlsVerifyMode: String = "verify",
    val pinnedCertSha256: String = "",
    val inboundSyncMode: String = "all",
    val inboundTags: List<String> = emptyList(),
    val outboundTag: String = "",
) {
    override fun toString() = "NodeDraft(id=$id, name=$name, $scheme://$address:$port$basePath, token=${if (apiToken.isEmpty()) "kept" else "***"})"
}

data class NodeTestResult(
    val online: Boolean,
    val error: String,
    val latencyMs: Long,
    val xrayState: String,
    val xrayVersion: String,
    val xrayError: String,
    val panelVersion: String,
)

/** An inbound on a node, offered for selective sync. */
data class NodeInbound(val tag: String, val remark: String, val protocol: String, val port: Int)

/** Where a new inbound can be placed. */
data class NodeChoice(val id: Int, val name: String, val online: Boolean, val serverId: Long?)
