package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.ClientRouting
import dev.autorasqr.xuimanager.domain.model.RoutingJobProgress
import dev.autorasqr.xuimanager.domain.model.RoutingState
import kotlinx.coroutines.flow.StateFlow

interface RoutingRepository {
    suspend fun load(serverId: Long): RoutingState

    /** Writes the subscription routing settings and restarts the panel so the subscription server picks them up. */
    suspend fun setClientRouting(serverId: Long, profile: ClientRouting)

    /**
     * Turns the server-side block of Russian destinations on (geo files over SSH, then Xray rules) or off (rules,
     * then files). Runs in the background; follow it with [serverBlockProgress].
     */
    fun setServerBlock(serverId: Long, enabled: Boolean)

    fun serverBlockProgress(serverId: Long): StateFlow<RoutingJobProgress?>
    fun clearServerBlockProgress(serverId: Long)
}
