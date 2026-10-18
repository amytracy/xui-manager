package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.panel.NodeMutation
import dev.autorasqr.xuimanager.data.panel.NodeProbe
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import dev.autorasqr.xuimanager.data.panel.PanelNode
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.domain.model.NodeDraft
import dev.autorasqr.xuimanager.domain.model.NodeInbound
import dev.autorasqr.xuimanager.domain.model.NodeStatus
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.NodesSnapshot
import dev.autorasqr.xuimanager.domain.model.PendingNode
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
    private val serverDao: ServerDao,
    private val secrets: SecretStore,
) : NodeRepository {

    override suspend fun supported(serverId: Long): Boolean = ioMapped { panels.forServer(serverId).second.capabilities.nodes }

    override suspend fun load(serverId: Long): NodesSnapshot = ioMapped {
        val nodes = api(serverId).listNodes()
        val own = serverDao.nodesOf(serverId).map { it.toDomain() }
        val registered = nodes.map { it.id }.toSet()
        NodesSnapshot(
            nodes = nodes.map { node -> node.toDomain(own.firstOrNull { it.nodeId == node.id && !node.transitive }?.id) },
            pending = own.filter { it.nodeId == null || it.nodeId !in registered }
                .map { PendingNode(it.id, it.name, it.host, deployed = it.panel != null) },
        )
    }

    override suspend fun get(serverId: Long, nodeId: Int): Node? = ioMapped {
        api(serverId).listNodes().firstOrNull { it.id == nodeId && !it.transitive }?.toDomain(null)
    }

    override suspend fun save(serverId: Long, draft: NodeDraft): Int = ioMapped {
        val api = api(serverId)
        if (draft.id == 0) {
            api.addNode(draft.toMutation())
        } else {
            api.updateNode(draft.id, draft.toMutation())
            draft.id
        }
    }

    override suspend fun test(serverId: Long, draft: NodeDraft): NodeTestResult = ioMapped {
        api(serverId).testNode(draft.toMutation()).toDomain()
    }

    override suspend fun fetchPin(serverId: Long, draft: NodeDraft): String = ioMapped {
        api(serverId).nodeCertFingerprint(draft.toMutation())
    }

    override suspend fun remoteInbounds(serverId: Long, draft: NodeDraft): List<NodeInbound> = ioMapped {
        api(serverId).nodeRemoteInbounds(draft.toMutation()).map { NodeInbound(it.tag, it.remark, it.protocol, it.port) }
    }

    override suspend fun outboundTags(serverId: Long): List<String> = ioMapped {
        val api = panels.forServer(serverId).second
        if (!api.capabilities.routing) return@ioMapped emptyList()
        outboundTagsOf(api.xrayTemplate().config)
    }

    override suspend fun setEnabled(serverId: Long, nodeId: Int, enabled: Boolean) = ioMapped {
        api(serverId).setNodeEnabled(nodeId, enabled)
    }

    override suspend fun probe(serverId: Long, nodeId: Int): NodeTestResult = ioMapped {
        api(serverId).probeNode(nodeId).toDomain()
    }

    override suspend fun delete(serverId: Long, nodeId: Int) = ioMapped {
        api(serverId).deleteNode(nodeId)
        serverDao.nodesOf(serverId).filter { it.nodeId == nodeId }.forEach {
            serverDao.updateNodeId(it.id, null, System.currentTimeMillis())
        }
    }

    override suspend fun register(masterId: Long, nodeServerId: Long): Int = ioMapped {
        val server = serverDao.get(nodeServerId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
        val panel = server.panel ?: throw AppException(AppError.PanelNotDeployed)
        val token = secrets.get(SecretKeys.panelToken(nodeServerId)) ?: throw AppException(AppError.ApiTokenFailed)
        val api = api(masterId)
        val nodes = api.listNodes().filterNot { it.transitive }
        // Registering again (after a redeploy or a failed attempt) updates the entry that points at this panel.
        val existing = nodes.firstOrNull { it.id == server.nodeId } ?: nodes.firstOrNull {
            it.address.equals(server.panelHost, ignoreCase = true) && it.port == panel.port
        }
        val mutation = registration(server, panel.port, panel.webBasePath, token, existing?.name ?: uniqueName(server.name, nodes))
        val id = if (existing != null) {
            api.updateNode(existing.id, mutation)
            existing.id
        } else {
            api.addNode(mutation)
        }
        serverDao.updateNodeId(nodeServerId, id, System.currentTimeMillis())
        id
    }

    override suspend fun choices(serverId: Long): List<NodeChoice> = ioMapped {
        val api = panels.forServer(serverId).second
        if (!api.capabilities.nodes) return@ioMapped emptyList()
        val own = serverDao.nodesOf(serverId)
        api.listNodes().filter { it.enabled && !it.transitive && it.id > 0 }.map { node ->
            NodeChoice(node.id, node.name, NodeStatus.of(node.status) == NodeStatus.ONLINE, own.firstOrNull { it.nodeId == node.id }?.id)
        }
    }

    override suspend fun webCert(serverId: Long, nodeId: Int): Pair<String, String>? = ioMapped { api(serverId).nodeWebCert(nodeId) }

    private suspend fun api(serverId: Long): PanelApi {
        val api = panels.forServer(serverId).second
        if (!api.capabilities.nodes) throw AppException(AppError.PanelError("needs 3x-ui v3"))
        return api
    }

    companion object {
        /** A panel the app deployed: reached like the app reaches it, over its Let's Encrypt certificate. */
        internal fun registration(server: Server, port: Int, basePath: String, token: String, name: String) = NodeMutation(
            name = name,
            remark = "XUI Manager",
            scheme = "https",
            address = server.panelHost,
            port = port,
            basePath = basePath,
            apiToken = token,
            enabled = true,
            allowPrivateAddress = false,
            tlsVerifyMode = "verify",
            pinnedCertSha256 = "",
            inboundSyncMode = "all",
            inboundTags = emptyList(),
            outboundTag = "",
        )

        /** Node names are unique on the panel. */
        internal fun uniqueName(wanted: String, nodes: List<PanelNode>): String {
            val base = wanted.trim().ifEmpty { "node" }
            val taken = nodes.map { it.name.lowercase() }.toSet()
            if (base.lowercase() !in taken) return base
            return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it.lowercase() !in taken }
        }

        /** Outbounds (without blackholes) and balancers, as the panel's node form offers them. */
        internal fun outboundTagsOf(config: JsonObject): List<String> {
            val outbounds = (config["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.string("protocol") != "blackhole" }
                .mapNotNull { it.string("tag")?.takeIf(String::isNotBlank) }
            val balancers = ((config["routing"] as? JsonObject)?.get("balancers") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("tag")?.takeIf(String::isNotBlank) }
            return (outbounds + balancers).distinct()
        }

        internal fun NodeDraft.toMutation() = NodeMutation(
            id = id,
            name = name,
            remark = remark,
            scheme = scheme,
            address = address,
            port = port,
            basePath = basePath,
            apiToken = apiToken.trim().ifEmpty { null },
            enabled = enabled,
            allowPrivateAddress = allowPrivateAddress,
            tlsVerifyMode = tlsVerifyMode,
            pinnedCertSha256 = pinnedCertSha256,
            inboundSyncMode = inboundSyncMode,
            inboundTags = inboundTags,
            outboundTag = outboundTag,
        )

        private fun PanelNode.toDomain(serverId: Long?) = Node(
            id = id, name = name, remark = remark, scheme = scheme, address = address, port = port, basePath = basePath,
            enabled = enabled, status = NodeStatus.of(status), latencyMs = latencyMs, cpuPercent = cpuPercent,
            memPercent = memPercent, uptimeSeconds = uptimeSeconds, xrayState = xrayState, xrayVersion = xrayVersion,
            xrayError = xrayError, panelVersion = panelVersion, lastError = lastError, inboundCount = inboundCount,
            clientCount = clientCount, onlineCount = onlineCount, hasApiToken = hasApiToken, tlsVerifyMode = tlsVerifyMode,
            pinnedCertSha256 = pinnedCertSha256, inboundSyncMode = inboundSyncMode, inboundTags = inboundTags,
            outboundTag = outboundTag, allowPrivateAddress = allowPrivateAddress, transitive = transitive, serverId = serverId,
        )

        private fun NodeProbe.toDomain() = NodeTestResult(
            online = online, error = error, latencyMs = latencyMs, xrayState = xrayState, xrayVersion = xrayVersion,
            xrayError = xrayError, panelVersion = panelVersion,
        )
    }
}
