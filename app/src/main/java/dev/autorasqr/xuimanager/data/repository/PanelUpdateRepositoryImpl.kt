package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelNode
import dev.autorasqr.xuimanager.data.panel.PanelUpdateInfo
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.data.panel.awaitOnline
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.PanelUpdateOffer
import dev.autorasqr.xuimanager.domain.model.PanelUpdateProgress
import dev.autorasqr.xuimanager.domain.model.PanelUpdateTarget
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.repository.PanelUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 3x-ui's own self-update (the official update.sh: settings, database and certificate stay as they are). Nodes are
 * updated through the main panel first, while it is still up to watch them, then the main panel itself.
 */
@Singleton
class PanelUpdateRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
    private val serverDao: ServerDao,
) : PanelUpdateRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<PanelUpdateProgress?>>()
    private val checks = ConcurrentHashMap<Long, UpdateCheck<PanelUpdateOffer>>()
    private val prompted = ConcurrentHashMap.newKeySet<Long>()

    override suspend fun check(serverId: Long, refresh: Boolean): UpdateCheck<PanelUpdateOffer> = withContext(Dispatchers.IO) {
        if (!refresh) checks[serverId]?.let { return@withContext it }
        val result = try {
            val (server, api) = panels.forServer(serverId)
            if (server.isNode || !api.capabilities.selfUpdate) {
                UpdateCheck.Unavailable()
            } else {
                val info = api.panelUpdateInfo()
                val nodes = if (api.capabilities.nodes) api.listNodes() else emptyList()
                offerFrom(server.name, info, nodes)?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No GitHub access from the server, panel down, older v3 without the endpoint. Not remembered, so the
            // next screen visit tries again.
            return@withContext UpdateCheck.Unavailable(e.toAppError())
        }
        checks[serverId] = result
        result
    }

    override fun shouldPrompt(serverId: Long): Boolean = serverId !in prompted

    override fun markPrompted(serverId: Long) {
        prompted += serverId
    }

    override fun progress(serverId: Long): StateFlow<PanelUpdateProgress?> = flowFor(serverId)

    override fun clearProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun start(serverId: Long, offer: PanelUpdateOffer) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = PanelUpdateProgress(offer.latestVersion, DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val api = panels.forServer(serverId).second
                val problems = mutableListOf<String>()
                if (offer.nodes.isNotEmpty()) problems += updateNodes(serverId, api, offer, log)
                if (offer.master != null) updateMaster(serverId, api, offer.latestVersion, log)
                if (problems.isNotEmpty()) throw AppException(AppError.PanelUpdateFailed(problems.joinToString("; ")))
                flow.update { it?.copy(state = DeployState.SUCCEEDED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                log("ERROR: $error")
                flow.update { it?.copy(state = DeployState.FAILED, error = error) }
            } finally {
                checks.remove(serverId)
            }
        }
    }

    /** Returns what went wrong, one entry per node that did not end up on [PanelUpdateOffer.latestVersion]. */
    private suspend fun updateNodes(serverId: Long, api: PanelApi, offer: PanelUpdateOffer, log: (String) -> Unit): List<String> {
        val names = offer.nodes.associate { it.nodeId!! to it.name }
        log("Updating 3x-ui on the nodes: " + names.values.joinToString())
        val results = api.updateNodePanels(names.keys.toList())
        val problems = mutableListOf<String>()
        val pending = mutableSetOf<Int>()
        results.forEach { result ->
            val name = names[result.id] ?: result.name
            if (result.ok) {
                pending += result.id
                log("$name: updater started")
            } else {
                val reason = result.error.ifBlank { "refused" }
                log("$name: $reason")
                problems += "$name: $reason"
            }
        }

        val deadline = System.currentTimeMillis() + NODE_TIMEOUT_MS
        while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            delay(NODE_POLL_MS)
            for (id in pending.toList()) {
                // A node restarting mid-update does not answer; keep asking until the deadline.
                val version = try {
                    api.probeNode(id).panelVersion
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                if ((PanelVersion.compare(version, offer.latestVersion) ?: -1) >= 0) {
                    pending -= id
                    val normalized = PanelVersion.normalize(version)
                    log("${names[id]}: now on $normalized")
                    serverDao.nodesOf(serverId).filter { it.nodeId == id }.forEach { node ->
                        serverDao.updatePanelVersion(node.id, normalized, System.currentTimeMillis())
                        panels.invalidate(node.id)
                    }
                }
            }
        }
        pending.forEach { id ->
            log("${names[id]}: still not on ${offer.latestVersion} after ${NODE_TIMEOUT_MS / 60_000} min")
            problems += "${names[id]}: not updated"
        }
        return problems
    }

    private suspend fun updateMaster(serverId: Long, api: PanelApi, latestVersion: String, log: (String) -> Unit) {
        log("Updating 3x-ui on the main panel to $latestVersion")
        val runId = api.startPanelUpdate()
        log("Updater started; the panel restarts when it is done")
        val deadline = System.currentTimeMillis() + MASTER_TIMEOUT_MS
        while (true) {
            delay(MASTER_POLL_MS)
            // While x-ui restarts the request fails; that is expected and not an error.
            val run = try {
                api.panelUpdateStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (run != null && run.runId == runId) {
                if (run.state == "success") break
                if (run.state == "failed") throw AppException(AppError.PanelUpdateFailed("update.sh exit code ${run.exitCode}"))
            }
            if (System.currentTimeMillis() > deadline) throw AppException(AppError.Timeout)
        }
        val status = api.awaitOnline(initialDelayMs = 2_000, timeoutMs = 120_000)
        val version = PanelVersion.normalize(status.panelVersion.ifBlank { latestVersion })
        serverDao.updatePanelVersion(serverId, version, System.currentTimeMillis())
        panels.invalidate(serverId)
        log("Main panel now runs $version")
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }

    companion object {
        private const val NODE_POLL_MS = 5_000L
        private const val NODE_TIMEOUT_MS = 10 * 60_000L
        private const val MASTER_POLL_MS = 3_000L
        private const val MASTER_TIMEOUT_MS = 20 * 60_000L

        /**
         * What to offer: the main panel when the panel itself reports a newer release, plus every enabled, online,
         * directly managed node on an older version than that release. Null when there is nothing to update.
         */
        internal fun offerFrom(masterName: String, info: PanelUpdateInfo, nodes: List<PanelNode>): PanelUpdateOffer? {
            val latest = info.latestVersion.trim()
            if (latest.isEmpty()) return null
            val master = if (info.updateAvailable) PanelUpdateTarget(masterName, PanelVersion.normalize(info.currentVersion)) else null
            val outdated = nodes
                .filter { it.enabled && it.status == "online" && !it.transitive }
                .filter { (PanelVersion.compare(it.panelVersion, latest) ?: 0) < 0 }
                .map { PanelUpdateTarget(it.name, PanelVersion.normalize(it.panelVersion), it.id) }
            if (master == null && outdated.isEmpty()) return null
            return PanelUpdateOffer(PanelVersion.normalize(latest), master, outdated)
        }
    }
}
