package dev.autorasqr.xuimanager.domain.model

enum class SshAuthType { PASSWORD, PRIVATE_KEY }

/** SSH host key pin in OpenSSH notation, e.g. algorithm = "ssh-ed25519", fingerprint = "SHA256:abc…". */
data class HostKeyPin(val algorithm: String, val fingerprint: String)

data class PanelInfo(
    val version: String,
    val port: Int,
    val webBasePath: String,
    val username: String,
    val subScheme: String,
    val subPort: Int,
    val subPath: String,
    val defaultInboundId: Int?,
    val deployedAt: Long,
    /** Name the panel certificate was issued for (domain or IP); null for panels deployed before it was recorded. */
    val host: String? = null,
    /** Local port of the self-steal decoy site, when self-steal is set up. */
    val selfstealPort: Int? = null,
)

data class Server(
    val id: Long,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val authType: SshAuthType,
    val hostKey: HostKeyPin?,
    val createdAt: Long,
    val updatedAt: Long,
    val panel: PanelInfo?,
    /** Optional domain pointing at [host]; the panel, subscriptions and self-steal use it. SSH always uses [host]. */
    val domain: String? = null,
    /** Set when this server was deployed as a node of that server's panel. */
    val masterServerId: Long? = null,
    /** The node's id on the master panel; null until it is registered there. */
    val nodeId: Int? = null,
) {
    val isNode: Boolean get() = masterServerId != null

    /**
     * Where the panel and subscriptions are reached: the name its certificate covers. Panels deployed before that
     * was recorded were always reached through [host].
     */
    val panelHost: String get() = panel?.let { it.host ?: host } ?: deployHost

    /** The name a new deployment issues the certificate for. */
    val deployHost: String get() = domain ?: host
}

/**
 * Form input for creating or editing a server. On edit, blank secret fields mean "keep the stored value".
 */
data class ServerDraft(
    val id: Long?,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val authType: SshAuthType,
    val password: String,
    val privateKey: String,
    val passphrase: String,
    /** Blank for none. */
    val domain: String = "",
    /** For a new server: deploy it as a node of this server's panel. */
    val masterServerId: Long? = null,
    /** For a new node server: the node it already is on the master panel (restored rather than deployed). */
    val nodeId: Int? = null,
)

/** What the user needs to sign in to the panel in a browser. [password] is null if it was never stored. */
data class PanelLogin(val url: String, val username: String, val password: String?) {
    override fun toString() = "PanelLogin(url=$url, username=$username, password=***)"
}

sealed interface SshCredentials {
    data class Password(val password: String) : SshCredentials {
        override fun toString() = "Password(***)"
    }

    data class PrivateKey(val pem: String, val passphrase: String?) : SshCredentials {
        override fun toString() = "PrivateKey(***)"
    }
}
