package dev.autorasqr.xuimanager.domain.model

/** One panel the update offer covers: the main panel ([nodeId] null) or one of its nodes. */
data class PanelUpdateTarget(val name: String, val currentVersion: String, val nodeId: Int? = null)

/** A newer 3x-ui release for the main panel and/or its nodes. */
data class PanelUpdateOffer(
    val latestVersion: String,
    /** Null when the main panel is already current and only nodes lag behind. */
    val master: PanelUpdateTarget?,
    val nodes: List<PanelUpdateTarget>,
)

data class PanelUpdateProgress(
    val targetVersion: String,
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
)

data class SystemUpdateProgress(
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
    /** The upgrade asked for a restart and the server reboots in about a minute. */
    val rebooting: Boolean = false,
)

/** Result of an update check for the app, the panel or Xray. */
sealed interface UpdateCheck<out T> {
    data object UpToDate : UpdateCheck<Nothing>
    data class Available<T>(val offer: T) : UpdateCheck<T>
    /** Not applicable here (v2 panel, node, no panel yet) when [error] is null, otherwise the check failed. */
    data class Unavailable(val error: AppError? = null) : UpdateCheck<Nothing>
}

/** A newer Xray-core release than the one running on the server. */
data class XrayUpdateOffer(val currentVersion: String, val latestVersion: String)

data class XrayUpdateProgress(
    val targetVersion: String,
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
)

/** A newer app release on GitHub; installed by hand from [releaseUrl]. */
data class AppUpdateOffer(val currentVersion: String, val latestVersion: String, val releaseUrl: String, val notes: String)

/** Removing everything the app put on a server (and its node servers), then the servers from the app. */
data class UninstallProgress(
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
    /** Servers already cleaned and deleted from the app; the rest stay for another try after a failure. */
    val removedServerIds: Set<Long> = emptySet(),
)

/** Reconnecting the app to a panel that is already set up on the server, without deploying it again. */
data class RestoreProgress(
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
)

/** Restarting the whole server on request, then waiting until it answers over SSH again. */
data class RebootProgress(
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
)
