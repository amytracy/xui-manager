package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.domain.model.SubBalancer
import dev.autorasqr.xuimanager.data.panel.awaitOnline
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import dev.autorasqr.xuimanager.domain.repository.SubscriptionRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
    private val serverDao: ServerDao,
) : SubscriptionRepository {

    private suspend fun api(serverId: Long) = panels.forServer(serverId).second

    override suspend fun supported(serverId: Long): Boolean = ioMapped { api(serverId).capabilities.routing }

    override suspend fun settings(serverId: Long): JsonObject = ioMapped { api(serverId).settings() }

    override suspend fun save(serverId: Long, changes: Map<String, JsonElement>) = ioMapped {
        if (changes.isEmpty()) return@ioMapped
        val api = api(serverId)
        api.patchSettings(changes)
        api.restartPanel()
        api.awaitOnline()
        // The server screen shows the subscription's scheme and port; client links are always built from the panel.
        val subscription = api.subscriptionSettings()
        val entity = serverDao.get(serverId) ?: return@ioMapped
        val panel = entity.panel ?: return@ioMapped
        val updated = panel.copy(subScheme = subscription.scheme, subPort = subscription.port, subPath = subscription.path)
        if (updated != panel) serverDao.update(entity.copy(panel = updated, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun balancers(serverId: Long): List<SubBalancer> = ioMapped { api(serverId).subBalancers() }

    override suspend fun balancerInbounds(serverId: Long): List<InboundSummary> = ioMapped {
        api(serverId).listInbounds()
            .filter { it.protocol in BALANCER_PROTOCOLS }
            .map { InboundSummary(it.id, it.remark, it.protocol, it.port, it.network, it.security) }
    }

    override suspend fun saveBalancer(serverId: Long, balancer: SubBalancer) = ioMapped { api(serverId).saveSubBalancer(balancer) }

    override suspend fun deleteBalancer(serverId: Long, id: Int) = ioMapped { api(serverId).deleteSubBalancer(id) }

    private companion object {
        /** SubBalancerFormModal: the JSON subscription builds proxy outbounds only for these. */
        val BALANCER_PROTOCOLS = setOf("shadowsocks", "vless", "vmess", "trojan", "hysteria", "wireguard", "masque")
    }
}
