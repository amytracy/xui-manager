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
