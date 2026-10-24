package dev.autorasqr.xuimanager.domain.model

enum class DeployMode {
    /** VLESS + Reality on TCP with XTLS Vision, port 443 or a free one, Reality target picked by the panel's scanner. */
    ONE_CLICK,

    /** Protocol, transport, port and Reality target chosen by the user, see [InboundConfig]. */
    MANUAL,
}

/** What to do when 3x-ui is already present on the server. */
enum class ExistingInstallAction {
    /** Run the installer over the existing panel: inbounds and clients stay, login, password and path are replaced. */
    UPDATE,

    /** Back up x-ui.db to /root/xui-backup and install from scratch. */
    CLEAN,

    /**
     * Keep the installed version, its port, path, inbounds and subscription settings. Only the login, password and
     * panel certificate are replaced so the app can pin and reach it. No inbound is created.
     */
    ADOPT,
}

enum class SudoMode { ROOT, NOPASSWD, PASSWORD, NONE }

data class DeployOptions(
    val mode: DeployMode,
    /** One-click mode: Reality target chosen by the user, or null to pick the best one automatically. */
    val sni: String?,
    val existingAction: ExistingInstallAction?,
    /** Manual mode only. */
    val manual: InboundConfig? = null,
    /** uTLS fingerprint written into the Reality inbound for client links. */
    val fingerprint: String = DEFAULT_FINGERPRINT,
    /** One-click on a server with a domain: Reality borrows the server's own decoy site. */
    val selfSteal: Boolean = false,
    /** The user's own index.html for the decoy site; null picks a random template. */
    val sitePage: ByteArray? = null,
    /** Hand the RoscomVPN routing profile to Happ/INCY so RU/BY traffic goes direct (3x-ui v3). */
    val clientRouting: Boolean = true,
)

const val DEFAULT_FINGERPRINT = "chrome"

data class PrecheckResult(
    val uid: Int,
    val gid: Int,
    val home: String,
    val sudo: SudoMode,
    val os: String,
    val osVersion: String,
    val arch: String,
    val systemd: Boolean,
    val existingInstalled: Boolean,
    val existingVersion: String?,
    /** IPv4 addresses the domain resolves to on the server; null when no domain was checked. */
    val domainIps: List<String>? = null,
    /** The server's own and public IPv4 addresses. */
    val serverIps: List<String> = emptyList(),
)

enum class DeployState { RUNNING, SUCCEEDED, FAILED }

/** Stages in the order they happen. Remote stages are reported by run.sh, the rest by the app. */
enum class DeployStage {
    CONNECT, UPLOAD, PREPARE, EXISTING, PORTS, FIREWALL, DOWNLOAD, INSTALL, CONFIGURE, SELFSTEAL, KEYS, RESTART, PANEL_SETUP, DONE;

    companion object {
        fun fromRemote(name: String): DeployStage? = entries.firstOrNull { it.name == name.trim() }
    }
}

enum class DeployWarning {
    /** No trusted certificate could be issued, the subscription is served over plain HTTP. */
    SUBSCRIPTION_PLAIN_HTTP,

    /** The Reality target could not be verified by the panel's scanner. */
    SNI_UNVERIFIED,

    /** The panel was deployed as a node but the main panel did not accept it; it can be registered again. */
    NODE_NOT_REGISTERED,
}

data class DeployProgress(
    val jobId: Long,
    val serverId: Long,
    val state: DeployState,
    val stage: DeployStage,
    val log: List<String>,
    val error: AppError?,
    val warnings: Set<DeployWarning>,
    /** Stages this kind of deploy does not run (adopting a panel skips the install). */
    val skippedStages: Set<DeployStage> = emptySet(),
)
