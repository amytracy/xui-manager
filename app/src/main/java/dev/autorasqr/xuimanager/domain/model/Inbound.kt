package dev.autorasqr.xuimanager.domain.model

import kotlinx.serialization.Serializable

enum class InboundProtocol { VLESS, TROJAN, VMESS, SHADOWSOCKS }

enum class InboundTransport { TCP, XHTTP, GRPC, WS }

enum class InboundSecurity {
    /** REALITY: TLS 1.3 handshake borrowed from a real site, no certificate on the server. */
    REALITY,

    /** No transport security; the protocol encrypts by itself (VMess, Shadowsocks 2022). */
    NONE,
}

/** Inbound requested in manual deploy mode. Security is implied by the protocol, see InboundMatrix. */
@Serializable
data class InboundConfig(
    val protocol: InboundProtocol,
    val transport: InboundTransport,
    val port: Int,
    /** Reality target host; required for REALITY, ignored otherwise. */
    val sni: String?,
)

/** An inbound as listed by the panel, for choosing where a client belongs. */
data class InboundSummary(
    val id: Int,
    val remark: String,
    val protocol: String,
    val port: Int,
    val network: String,
    val security: String,
)

/** An inbound as the inbounds screen shows it. */
data class InboundItem(
    val id: Int,
    val remark: String,
    val protocol: String,
    val network: String,
    val security: String,
    val port: Int,
    val enabled: Boolean,
    val up: Long,
    val down: Long,
    val clientCount: Int,
    val reality: RealityView?,
    /** Reality forwards probes to this server's own decoy site. */
    val selfSteal: Boolean,
    /** The node hosting the inbound; null for the panel's own inbounds. */
    val nodeId: Int? = null,
    val nodeName: String? = null,
) {
    /** The quick Reality editor handles VLESS over raw TCP with Reality; anything else needs the full editor. */
    val quickEditable: Boolean get() = reality != null && protocol == "vless" && network in setOf("tcp", "raw")
}

data class RealityView(val sni: String, val target: String, val fingerprint: String)

data class InboundsSnapshot(
    val items: List<InboundItem>,
    /** The panel can probe Reality targets from the server (3x-ui v3). */
    val canCheckSni: Boolean,
    val domain: String?,
    /** Local port of the self-steal site when it is set up. */
    val selfStealPort: Int?,
    /** Nodes a new inbound can be placed on (3x-ui v3); empty without nodes. */
    val nodes: List<NodeChoice> = emptyList(),
)

/** Quick VLESS + TCP + Reality inbound. */
data class RealityDraft(
    val remark: String,
    val port: Int,
    val sni: String,
    val fingerprint: String,
    /** Node to place the inbound on; null for the panel's own server. */
    val nodeId: Int? = null,
)

data class SniCheck(val host: String, val usable: Boolean, val latencyMs: Long)

enum class SelfStealAction { INSTALL, REMOVE }

data class SelfStealProgress(
    val action: SelfStealAction,
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
    /** Set after a successful install: the inbound that now uses the decoy site. */
    val inboundId: Int? = null,
)
