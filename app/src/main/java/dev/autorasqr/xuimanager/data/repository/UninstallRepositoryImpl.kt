package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.RemoteJob
import dev.autorasqr.xuimanager.data.deploy.ServerScripts
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.UninstallProgress
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.repository.UninstallRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UninstallRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val servers: ServerRepository,
    private val panels: PanelApiProvider,
    private val scripts: ServerScripts,
) : UninstallRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<UninstallProgress?>>()

    override fun progress(serverId: Long): StateFlow<UninstallProgress?> = flowFor(serverId)

    override fun clearProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun start(serverId: Long) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = UninstallProgress(DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
                // Nodes first: deleting a node from the app takes it off the main panel, which must still be there.
                val targets = serverDao.nodesOf(serverId).map { it.toDomain() } + server
                for (target in targets) {
                    log("== ${target.name} (${target.host}) ==")
                    val ports = panelPorts(target)
                    scripts.run(
                        server = target,
                        script = "uninstall.sh",
                        checkDomain = false,
                        timeoutMs = DeployConfig.UNINSTALL_TIMEOUT_MS,
                        params = { RemoteJob.uninstallEnv(it.uid, it.gid, target.sshPort, ports) },
                        log = log,
                        libs = listOf("selfsteal.sh"),
                        removeJobsAfter = true,
                    )
                    servers.delete(target.id)
                    flow.update { it?.copy(removedServerIds = it.removedServerIds + target.id) }
                    log("${target.name} is clean and removed from the app")
                }
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

    /**
     * Ports the panel opened: its own, the subscriptions' and its inbounds' as the panel lists them. Inbounds are
     * looked up best effort — uninstall.sh also closes whatever x-ui and Xray are found listening on.
     */
    private suspend fun panelPorts(server: Server): List<Int> {
        val panel = server.panel ?: return emptyList()
        val inbounds = withTimeoutOrNull(INBOUND_LOOKUP_MS) {
            runCatching {
                val master = server.masterServerId
                if (master == null) {
                    panels.forServer(server.id).second.listInbounds().filter { it.nodeId == null }
                } else {
                    val nodeId = server.nodeId ?: return@runCatching emptyList()
                    panels.forServer(master).second.listInbounds().filter { it.nodeId == nodeId }
                }
            }.getOrNull()
        }.orEmpty()
        return listOf(panel.port, panel.subPort) + inbounds.map { it.port }
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }

    private companion object {
        const val INBOUND_LOOKUP_MS = 20_000L
    }
}
