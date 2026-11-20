package dev.autorasqr.xuimanager.data.transfer

import kotlinx.serialization.Serializable

/**
 * Everything another phone needs to manage the same servers: every server of the app (the home server and its node
 * servers) with its SSH and panel secrets, plus the app's look. Deploy job history is not part of it.
 */
@Serializable
data class TransferBundle(
    val v: Int = VERSION,
    val createdAt: Long,
    val servers: List<TransferServer>,
    /** [dev.autorasqr.xuimanager.domain.model.UiStyle] name. */
    val uiStyle: String? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class TransferServer(
    /** The server's id on the old phone; only links node servers to their master inside the bundle. */
    val ref: Long,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    /** [dev.autorasqr.xuimanager.domain.model.SshAuthType] name. */
    val authType: String,
    val hostKeyAlgorithm: String? = null,
    val hostKeyFingerprint: String? = null,
    val domain: String? = null,
    /** [ref] of the master server for a node server. */
    val masterRef: Long? = null,
    val nodeId: Int? = null,
    val panel: TransferPanel? = null,
    val sshPassword: String? = null,
    val sshPrivateKey: String? = null,
    val sshPassphrase: String? = null,
    val panelPassword: String? = null,
    val panelToken: String? = null,
) {
    override fun toString() = "TransferServer(ref=$ref, name=$name, host=$host, masterRef=$masterRef, secrets=***)"
}

@Serializable
data class TransferPanel(
    val version: String,
    val port: Int,
    val webBasePath: String,
    val username: String,
    val subScheme: String,
    val subPort: Int,
    val subPath: String,
    val defaultInboundId: Int? = null,
    val deployedAt: Long,
    val host: String? = null,
    val selfstealPort: Int? = null,
)
