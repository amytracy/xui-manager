package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.PanelEmbedded
import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.Fingerprints
import dev.autorasqr.xuimanager.data.deploy.RandomTokens
import dev.autorasqr.xuimanager.data.deploy.RemoteJob
import dev.autorasqr.xuimanager.data.deploy.ServerScripts
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.inbound.InboundFactory
import dev.autorasqr.xuimanager.data.inbound.InboundRequest
import dev.autorasqr.xuimanager.data.inbound.SelfStealTarget
import dev.autorasqr.xuimanager.data.inbound.hostName
import dev.autorasqr.xuimanager.data.panel.InboundJson
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelInbound
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.InboundItem
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import dev.autorasqr.xuimanager.domain.model.InboundsSnapshot
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.domain.model.NodeStatus
import dev.autorasqr.xuimanager.domain.model.RealityView
import dev.autorasqr.xuimanager.domain.model.SelfStealAction
import dev.autorasqr.xuimanager.domain.model.SelfStealIssue
import dev.autorasqr.xuimanager.domain.model.SelfStealProgress
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.SniCheck
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.InboundRepository
import dev.autorasqr.xuimanager.domain.usecase.HostValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboundRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
    private val serverDao: ServerDao,
    private val factory: InboundFactory,
    private val random: RandomTokens,
    private val scripts: ServerScripts,
) : InboundRepository {

    /** Self-steal keeps running when the screen is left; the remote part survives the app as well. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<SelfStealProgress?>>()

    override suspend fun load(serverId: Long): InboundsSnapshot = ioMapped {
        val (server, api) = panels.forServer(serverId)
        val inbounds = api.listInbounds()
        // Node names are a nicety: an unreachable node list must not hide the inbounds.
        val nodes = if (api.capabilities.nodes) runCatching { nodeChoices(serverId, api) }.getOrDefault(emptyList()) else emptyList()
        val names = nodes.associate { it.id to it.name }
        val nodeServers = serverDao.nodesOf(serverId).map { it.toDomain() }.filter { it.nodeId != null && it.panel != null }
        InboundsSnapshot(
            items = inbounds.map { ib ->
                val nodeServer = ib.nodeId?.let { id -> nodeServers.firstOrNull { it.nodeId == id } }
                ib.toItem(server, ib.nodeId?.let { id -> names[id] ?: "#$id" }, nodeServer)
            },
            canCheckSni = api.capabilities.realityScan,
            domain = server.domain,
            selfStealPort = server.panel?.selfstealPort,
            nodes = nodes,
        )
    }

    override suspend fun setEnabled(serverId: Long, inboundId: Int, enabled: Boolean) = ioMapped {
        panels.forServer(serverId).second.setInboundEnabled(inboundId, enabled)
    }

    override suspend fun delete(serverId: Long, inboundId: Int) = ioMapped {
        val (server, api) = panels.forServer(serverId)
        api.deleteInbound(inboundId)
        if (server.panel?.defaultInboundId == inboundId) updatePanel(serverId) { it.copy(defaultInboundId = null) }
    }

    override suspend fun updateReality(serverId: Long, inboundId: Int, sni: String, fingerprint: String) = ioMapped {
        val api = panels.forServer(serverId).second
        val host = validateSni(sni)
        val current = api.getInbound(inboundId)
        api.updateInbound(inboundId, InboundJson.patchReality(current, "$host:443", host, fingerprint(fingerprint)))
        api.restartXray()
    }

    override suspend fun checkSni(serverId: Long, host: String, nodeId: Int?): SniCheck? = ioMapped {
        val master = panels.forServer(serverId).second
        val api = nodeScanner(serverId, nodeId) ?: master
        if (!api.capabilities.realityScan) return@ioMapped null
        val name = validateSni(host)
        val verdict = factory.scan(api, listOf(name)) {}.firstOrNull()
        SniCheck(verdict?.hostName() ?: name, verdict?.feasible == true, verdict?.latencyMs ?: 0)
    }

    override fun selfStealProgress(serverId: Long): StateFlow<SelfStealProgress?> = flowFor(serverId)

    override fun clearSelfStealProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun installSelfSteal(serverId: Long, convertInboundId: Int?, fingerprint: String, sitePage: ByteArray?) =
        startSelfSteal(serverId, SelfStealAction.INSTALL) { log ->
            val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
            val domain = server.domain ?: throw AppException(AppError.SelfStealFailed(SelfStealIssue.NO_DOMAIN))
            val port = runSelfStealScript(server, "install", domain, log, sitePage)
                ?: throw AppException(AppError.Unexpected("result.env has no SELFSTEAL_PORT"))
            updatePanel(serverId) { it.copy(selfstealPort = port) }

            val api = panels.forServer(serverId).second
            val target = SelfStealTarget(domain, port)
            val fp = fingerprint(fingerprint)
            val inboundId = if (convertInboundId != null) {
                log("Moving inbound #$convertInboundId onto the decoy site")
                val current = api.getInbound(convertInboundId)
                api.updateInbound(convertInboundId, InboundJson.patchReality(current, target.target, domain, fp))
                convertInboundId
            } else {
                val used = api.listInbounds().filter { it.nodeId == null }.map { it.port }.toSet()
                val inboundPort = if (443 !in used) 443 else generateSequence { random.port() }.first { it !in used && it != server.sshPort }
                factory.create(
                    api,
                    InboundRequest(
                        remark = DeployConfig.SELFSTEAL_REMARK,
                        protocol = InboundProtocol.VLESS,
                        transport = InboundTransport.TCP,
                        port = inboundPort,
                        sni = null,
                        fingerprint = fp,
                        selfSteal = target,
                    ),
                    serverKeys = null,
                    log = log,
                ).id
            }
            api.restartXray()
            log("Xray restarted; clients need to refresh their subscription")
            inboundId
        }

    override fun updateSitePage(serverId: Long, sitePage: ByteArray?, siteServerId: Long) =
        startSelfSteal(serverId, SelfStealAction.PAGE) { log ->
            val server = serverDao.get(siteServerId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
            if (siteServerId != serverId) log("Decoy site of ${server.name} (${server.host})")
            val domain = server.domain ?: throw AppException(AppError.SelfStealFailed(SelfStealIssue.NO_DOMAIN))
            runSelfStealScript(server, "page", domain, log, sitePage)
            log(if (sitePage != null) "Your page is on the decoy site" else "The decoy site has a new random page")
            null
        }

    override fun removeSelfSteal(serverId: Long) =
        startSelfSteal(serverId, SelfStealAction.REMOVE) { log ->
            val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
            runSelfStealScript(server, "remove", server.domain ?: "", log)
            updatePanel(serverId) { it.copy(selfstealPort = null) }
            null
        }

    private fun startSelfSteal(serverId: Long, action: SelfStealAction, block: suspend (log: (String) -> Unit) -> Int?) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = SelfStealProgress(action, DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val inboundId = block(log)
                flow.update { it?.copy(state = DeployState.SUCCEEDED, inboundId = inboundId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                log("ERROR: $error")
                flow.update { it?.copy(state = DeployState.FAILED, error = error) }
            }
        }
    }

    /** Runs selfsteal.sh as root on the server and returns SELFSTEAL_PORT from its result. */
    private suspend fun runSelfStealScript(
        server: Server,
        action: String,
        domain: String,
        log: (String) -> Unit,
        sitePage: ByteArray? = null,
    ): Int? =
        scripts.run(
            server = server,
            script = "selfsteal.sh",
            checkDomain = action == "install",
            timeoutMs = DeployConfig.SELFSTEAL_TIMEOUT_MS,
            params = { RemoteJob.selfStealEnv(action, domain, it.uid, it.gid) },
            log = log,
            extraFiles = sitePage?.let { mapOf(DeployConfig.SITE_PAGE_FILE to it) }.orEmpty(),
        )["SELFSTEAL_PORT"]?.toIntOrNull()

    private suspend fun updatePanel(serverId: Long, change: (PanelEmbedded) -> PanelEmbedded) {
        val entity = serverDao.get(serverId) ?: return
        val panel = entity.panel ?: return
        serverDao.update(entity.copy(panel = change(panel), updatedAt = System.currentTimeMillis()))
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }

    private fun validateSni(value: String): String {
        val host = value.trim().lowercase()
        if (!HostValidator.isHostname(host)) throw AppException(AppError.Validation(ValidationField.SNI))
        return host
    }

    /** For a node the app deployed, its own panel, so Reality targets are probed from the node itself. */
    private suspend fun nodeScanner(serverId: Long, nodeId: Int?): PanelApi? {
        if (nodeId == null) return null
        val own = serverDao.nodesOf(serverId).firstOrNull { it.nodeId == nodeId && it.panel != null } ?: return null
        return runCatching { panels.forServer(own.id).second }.getOrNull()
    }

    private suspend fun nodeChoices(serverId: Long, api: PanelApi): List<NodeChoice> {
        val own = serverDao.nodesOf(serverId)
        return api.listNodes().filter { !it.transitive && it.id > 0 }.map { node ->
            NodeChoice(node.id, node.name, node.enabled && NodeStatus.of(node.status) == NodeStatus.ONLINE, own.firstOrNull { it.nodeId == node.id }?.id)
        }
    }

    private fun fingerprint(value: String) = value.takeIf { it in Fingerprints.all } ?: Fingerprints.all.first()
}

/** [nodeServer] is the node's own server in the app, when the inbound is on a node the app reaches over SSH. */
internal fun PanelInbound.toItem(server: Server, nodeName: String?, nodeServer: Server? = null): InboundItem {
    val reality = reality
    // The decoy site belongs to the server running the inbound: this one, or the node's own server; a node the
    // app can't reach keeps its loopback target to itself.
    val host = if (nodeId == null) server else nodeServer
    val selfSteal = host != null && reality != null && reality.isLoopback &&
        (reality.targetPort == host.panel?.selfstealPort || host.domain?.let { it in reality.serverNames } == true)
    return InboundItem(
        id = id,
        remark = remark,
        protocol = protocol,
        network = network,
        security = security,
        port = port,
        enabled = enabled,
        up = up,
        down = down,
        clientCount = clientCount,
        reality = reality?.let { RealityView(it.serverNames.firstOrNull().orEmpty(), it.target, it.fingerprint) },
        selfSteal = selfSteal,
        nodeId = nodeId,
        nodeName = nodeName,
        siteServerId = host?.id?.takeIf { selfSteal },
        siteDomain = host?.domain?.takeIf { selfSteal },
    )
}
