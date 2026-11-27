package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeAction
import dev.autorasqr.xuimanager.domain.model.NodeBulkProgress
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.repository.NodeBulkRepository
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import dev.autorasqr.xuimanager.domain.repository.PanelUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.ServerPowerRepository
import dev.autorasqr.xuimanager.domain.repository.SystemUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.XrayUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands each node to the repository that already does the job for a single server and waits for it, so the node's own
 * server screen shows the same progress. Everything but the 3x-ui update needs the node's server in the app (SSH and
 * its own panel); 3x-ui goes through the main panel for every node at once.
 */
@Singleton
class NodeBulkRepositoryImpl internal constructor(
    private val nodes: NodeRepository,
    private val panelUpdates: PanelUpdateRepository,
    private val status: PanelStatusRepository,
    private val power: ServerPowerRepository,
    private val xrayUpdates: XrayUpdateRepository,
    private val systemUpdates: SystemUpdateRepository,
    private val scope: CoroutineScope,
) : NodeBulkRepository {

    @Inject constructor(
        nodes: NodeRepository,
        panelUpdates: PanelUpdateRepository,
        status: PanelStatusRepository,
        power: ServerPowerRepository,
        xrayUpdates: XrayUpdateRepository,
        systemUpdates: SystemUpdateRepository,
    ) : this(nodes, panelUpdates, status, power, xrayUpdates, systemUpdates, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val progress = ConcurrentHashMap<Long, MutableStateFlow<NodeBulkProgress?>>()

    override fun progress(masterId: Long): StateFlow<NodeBulkProgress?> = flowFor(masterId)

    override fun clear(masterId: Long) {
        flowFor(masterId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun start(masterId: Long, action: NodeAction) {
        val flow = flowFor(masterId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = NodeBulkProgress(action, DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val problems = run(masterId, action, log)
                if (problems.isNotEmpty()) throw AppException(AppError.NodeActionsFailed(problems.joinToString()))
                log("Done")
                flow.update { it?.copy(state = DeployState.SUCCEEDED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                log("ERROR: $error")
                flow.update { it?.copy(state = DeployState.FAILED, error = error) }
            }
        }
    }

    /** Returns the names of the nodes that did not make it. */
    private suspend fun run(masterId: Long, action: NodeAction, log: (String) -> Unit): List<String> {
        if (action == NodeAction.UPDATE_PANEL) return updatePanels(masterId, log)
        val targets = nodes.load(masterId).nodes.filter { !it.transitive && it.enabled }
        if (targets.isEmpty()) {
            log("No enabled nodes")
            return emptyList()
        }
        val problems = mutableListOf<String>()
        for (node in targets) {
            val serverId = node.serverId
            if (serverId == null) {
                log("${node.name}: no SSH access in the app, skipped")
                continue
            }
            log("${node.name}: ${action.name.lowercase().replace('_', ' ')}")
            try {
                runOne(node, serverId, action, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                log("${node.name}: ERROR $error")
                problems += node.name
            }
        }
        return problems
    }

    private suspend fun runOne(node: Node, serverId: Long, action: NodeAction, log: (String) -> Unit) {
        when (action) {
            NodeAction.RESTART_XRAY -> {
                status.restartXray(serverId)
                log("${node.name}: Xray restarted")
            }
            NodeAction.RESTART_PANEL -> {
                power.restartPanel(serverId)
                log("${node.name}: panel restarted")
            }
            NodeAction.UPDATE_XRAY -> when (val check = xrayUpdates.check(serverId, refresh = true)) {
                UpdateCheck.UpToDate -> log("${node.name}: Xray is up to date")
                is UpdateCheck.Unavailable -> throw AppException(check.error ?: AppError.Unexpected("Xray update not available"))
                is UpdateCheck.Available -> {
                    log("${node.name}: Xray ${check.offer.currentVersion} -> ${check.offer.latestVersion}")
                    if (xrayUpdates.progress(serverId).value?.state != DeployState.RUNNING) xrayUpdates.start(serverId, check.offer)
                    val done = xrayUpdates.progress(serverId).first { it != null && it.state != DeployState.RUNNING }!!
                    done.error?.let { throw AppException(it) }
                    log("${node.name}: Xray updated")
                }
            }
            NodeAction.SYSTEM_UPDATE -> {
                if (systemUpdates.progress(serverId).value?.state != DeployState.RUNNING) systemUpdates.start(serverId)
                val done = systemUpdates.progress(serverId).first { it != null && it.state != DeployState.RUNNING }!!
                done.error?.let { throw AppException(it) }
                log("${node.name}: system updated" + if (done.rebooting) ", rebooting in a minute" else "")
            }
            NodeAction.REBOOT -> {
                if (power.rebootProgress(serverId).value?.state != DeployState.RUNNING) power.reboot(serverId)
                val done = power.rebootProgress(serverId).first { it != null && it.state != DeployState.RUNNING }!!
                done.error?.let { throw AppException(it) }
                log("${node.name}: back online")
            }
            NodeAction.UPDATE_PANEL -> error("handled for all nodes at once")
        }
    }

    /** Every outdated node through the main panel's own updater; the main panel itself is left alone. */
    private suspend fun updatePanels(masterId: Long, log: (String) -> Unit): List<String> {
        val offer = when (val check = panelUpdates.check(masterId, refresh = true)) {
            is UpdateCheck.Available -> check.offer
            UpdateCheck.UpToDate -> null
            is UpdateCheck.Unavailable -> throw AppException(check.error ?: AppError.Unexpected("3x-ui update not available"))
        }
        if (offer == null || offer.nodes.isEmpty()) {
            log("3x-ui is up to date on every node")
            return emptyList()
        }
        val updates = panelUpdates.progress(masterId)
        if (updates.value?.state != DeployState.RUNNING) panelUpdates.start(masterId, offer.copy(master = null))
        val done = updates.first { it != null && it.state != DeployState.RUNNING }!!
        done.log.forEach(log)
        done.error?.let { throw AppException(it) }
        return emptyList()
    }

    private fun flowFor(masterId: Long) = progress.getOrPut(masterId) { MutableStateFlow(null) }
}
