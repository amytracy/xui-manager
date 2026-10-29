package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.RemoteJob
import dev.autorasqr.xuimanager.data.deploy.ServerScripts
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.awaitOnline
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshTargets
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.RebootProgress
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.ServerPowerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerPowerRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
    private val serverDao: ServerDao,
    private val scripts: ServerScripts,
    private val ssh: SshConnector,
    private val targets: SshTargets,
) : ServerPowerRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<RebootProgress?>>()

    override suspend fun restartPanel(serverId: Long) = ioMapped {
        val api = panels.forServer(serverId).second
        api.restartPanel()
        api.awaitOnline()
        Unit
    }

    override fun rebootProgress(serverId: Long): StateFlow<RebootProgress?> = flowFor(serverId)

    override fun clearRebootProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun reboot(serverId: Long) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = RebootProgress(DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
                // Uptime going down is what tells a finished reboot from a server that never went away.
                val before = uptime(server) ?: throw AppException(AppError.Unexpected("cannot read the server's uptime"))
                scripts.run(
                    server = server,
                    script = "reboot.sh",
                    checkDomain = false,
                    timeoutMs = DeployConfig.REBOOT_SCRIPT_TIMEOUT_MS,
                    params = { RemoteJob.rebootEnv(it.uid, it.gid) },
                    log = log,
                )
                log("Waiting for the server to come back")
                awaitReboot(server, before)
                log("The server is back online")
                if (server.panel != null) {
                    try {
                        panels.forServer(serverId).second.awaitOnline(initialDelayMs = 0, timeoutMs = DeployConfig.REBOOT_PANEL_WAIT_MS)
                        log("The panel answers again")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("WARNING: the panel does not answer yet: ${e.toAppError()}")
                    }
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

    private suspend fun awaitReboot(server: Server, before: Long) {
        val deadline = System.currentTimeMillis() + DeployConfig.REBOOT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(REBOOT_POLL_MS)
            val now = uptime(server) ?: continue
            if (now < before) return
        }
        throw AppException(AppError.RebootFailed("timeout"))
    }

    /** Seconds since boot, or null while the server can't be reached. */
    private suspend fun uptime(server: Server): Long? = try {
        ssh.withSession(targets.forServer(server)) { it.execSh("cut -d. -f1 /proc/uptime", timeoutSeconds = 15) }
            .stdoutText.trim().toLongOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }

    private companion object {
        const val REBOOT_POLL_MS = 5_000L
    }
}
