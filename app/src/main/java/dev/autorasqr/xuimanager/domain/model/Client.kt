package dev.autorasqr.xuimanager.domain.model

data class Client(
    val email: String,
    val enabled: Boolean,
    val subId: String,
    /** 0 means unlimited. */
    val totalBytes: Long,
    /**
     * Epoch millis; 0 means never expires. Negative values are 3x-ui's "delayed start": the client expires
     * -expiryTime milliseconds after its first connection.
     */
    val expiryTime: Long,
    val up: Long,
    val down: Long,
    val online: Boolean,
    val inboundIds: List<Int>,
    val comment: String,
) {
    val usedBytes: Long get() = up + down
}

data class ClientDraft(
    val email: String,
    /** 0 means unlimited. */
    val totalBytes: Long,
    /** Same encoding as [Client.expiryTime]. */
    val expiryTime: Long,
    val subId: String,
    val inboundIds: List<Int>,
    val enabled: Boolean = true,
)

/** Everything the clients screen needs in one round of requests. */
data class ClientsSnapshot(
    val clients: List<Client>,
    val inbounds: List<InboundSummary>,
    /** False for 3x-ui 2.x, where a client lives in exactly one inbound. */
    val multiInbound: Boolean,
    /** Inbound created by the app's deploy, preselected for new clients. */
    val defaultInboundId: Int?,
)
