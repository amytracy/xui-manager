package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.SubBalancer
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The panel's subscription settings and JSON-subscription balancers (3x-ui v3). */
interface SubscriptionRepository {
    /** False for panels older than v3, whose settings the screen does not cover. */
    suspend fun supported(serverId: Long): Boolean

    /** All panel settings (AllSetting) as the panel returns them. */
    suspend fun settings(serverId: Long): JsonObject

    /** Writes [changes] over the current settings and restarts the panel, which reads them at start. */
    suspend fun save(serverId: Long, changes: Map<String, JsonElement>)

    suspend fun balancers(serverId: Long): List<SubBalancer>

    /** Inbounds a balancer can use: protocols the JSON subscription builds outbounds for. */
    suspend fun balancerInbounds(serverId: Long): List<InboundSummary>
    suspend fun saveBalancer(serverId: Long, balancer: SubBalancer)
    suspend fun deleteBalancer(serverId: Long, id: Int)
}
