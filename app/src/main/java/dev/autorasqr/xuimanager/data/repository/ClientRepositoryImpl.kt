package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.panel.ClientChanges
import dev.autorasqr.xuimanager.data.panel.NewClient
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelInbound
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.Client
import dev.autorasqr.xuimanager.domain.model.ClientDraft
import dev.autorasqr.xuimanager.domain.model.ClientsSnapshot
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.ClientRepository
import dev.autorasqr.xuimanager.domain.usecase.ClientDraftValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
) : ClientRepository {

    override suspend fun load(serverId: Long): ClientsSnapshot = ioMapped {
        val (server, api) = panels.forServer(serverId)
        coroutineScope {
            val clients = async { api.listClients() }
            val inbounds = async { api.listInbounds() }
            val online = async {
                try {
                    api.onlineEmails()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptySet()
                }
            }
            val onlineEmails = online.await()
            ClientsSnapshot(
                clients = clients.await()
                    .map { client ->
                        Client(
                            email = client.email,
                            enabled = client.enabled,
                            subId = client.subId,
                            totalBytes = client.totalBytes,
                            expiryTime = client.expiryTime,
                            up = client.up,
                            down = client.down,
                            online = client.email in onlineEmails,
                            inboundIds = client.inboundIds,
                            comment = client.comment,
                        )
                    }
                    .sortedBy { it.email.lowercase() },
                inbounds = inbounds.await().filter(::canHoldClients).map(::summary),
                multiInbound = api.capabilities.multiInboundClients,
                defaultInboundId = server.panel?.defaultInboundId,
            )
        }
    }

    override suspend fun createClient(serverId: Long, draft: ClientDraft) = ioMapped {
        val (_, api) = panels.forServer(serverId)
        val existing = api.listClients().map { it.email }
        val inbounds = resolveInbounds(api, draft.inboundIds)
        ClientDraftValidator.validate(draft, existing)?.let { throw AppException(AppError.Validation(it)) }
        api.addClient(
            NewClient(
                email = draft.email,
                subId = draft.subId,
                totalBytes = draft.totalBytes,
                expiryTime = draft.expiryTime,
                flow = if (inbounds.any { it.supportsVision }) DeployConfig.VISION_FLOW else "",
                inboundIds = inbounds.map { it.id },
                enabled = draft.enabled,
            ),
        )
    }

    override suspend fun updateClient(serverId: Long, originalEmail: String, draft: ClientDraft) = ioMapped {
        val (_, api) = panels.forServer(serverId)
        val others = api.listClients().map { it.email }.filterNot { it.equals(originalEmail, ignoreCase = true) }
        val inbounds = resolveInbounds(api, draft.inboundIds)
        ClientDraftValidator.validate(draft, others)?.let { throw AppException(AppError.Validation(it)) }
        api.updateClient(
            originalEmail,
            ClientChanges(
                email = draft.email,
                subId = draft.subId,
                totalBytes = draft.totalBytes,
                expiryTime = draft.expiryTime,
                enabled = draft.enabled,
                inboundIds = inbounds.map { it.id },
            ),
        )
    }

    override suspend fun deleteClient(serverId: Long, email: String) = ioMapped {
        panels.forServer(serverId).second.deleteClient(email)
    }

    override suspend fun setEnabled(serverId: Long, email: String, enabled: Boolean) = ioMapped {
        panels.forServer(serverId).second.setClientEnabled(email, enabled)
    }

    override suspend fun resetTraffic(serverId: Long, email: String) = ioMapped {
        panels.forServer(serverId).second.resetClientTraffic(email)
    }

    override suspend fun subscriptionUrl(serverId: Long, subId: String): String = ioMapped {
        val (server, api) = panels.forServer(serverId)
        api.subscriptionSettings().url(server.panelHost, subId)
    }

    /** Checks the requested inbounds exist and, on 2.x, that there is exactly one. */
    private suspend fun resolveInbounds(api: PanelApi, ids: List<Int>): List<PanelInbound> {
        if (ids.isEmpty()) throw AppException(AppError.Validation(ValidationField.INBOUNDS))
        val available = api.listInbounds().associateBy { it.id }
        val selected = ids.distinct().map { id -> available[id] ?: throw AppException(AppError.NoInbound) }
        if (!api.capabilities.multiInboundClients && selected.size != 1) {
            throw AppException(AppError.Validation(ValidationField.INBOUNDS))
        }
        return selected
    }

    /** Protocols whose clients the app can create; others (socks, wireguard, …) are left to the web panel. */
    private fun canHoldClients(inbound: PanelInbound) = inbound.protocol in setOf("vless", "vmess", "trojan", "shadowsocks")

    private fun summary(inbound: PanelInbound) = InboundSummary(
        id = inbound.id,
        remark = inbound.remark,
        protocol = inbound.protocol,
        port = inbound.port,
        network = inbound.network,
        security = inbound.security,
    )
}
