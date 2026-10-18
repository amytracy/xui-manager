package dev.autorasqr.xuimanager.domain.model

/** Routing profile the subscription hands to Happ and INCY (github.com/hydraponique/roscomvpn-routing). */
enum class ClientRouting {
    OFF,

    /** RU/BY sites and banks direct, YouTube/Telegram/GitHub and everything else through the VPN, ads blocked. */
    ROSCOM_DEFAULT,

    /** Direct only for services and IPs on Russia's whitelists; everything else through the VPN. */
    ROSCOM_WHITELIST,

    /** Something else is configured in the panel; shown, not managed. */
    CUSTOM,
}

data class RoutingState(
    /** 3x-ui v3 or newer. */
    val supported: Boolean,
    val clientRouting: ClientRouting,
    /** Xray on the server refuses Russian destinations (RoscomVPN geo files). */
    val serverBlocksRu: Boolean,
)

data class RoutingJobProgress(
    val enabling: Boolean,
    val state: DeployState,
    val log: List<String>,
    val error: AppError?,
)
