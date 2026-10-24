package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.RemoteJob
import dev.autorasqr.xuimanager.data.deploy.ServerScripts
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.SystemUpdateProgress
import dev.autorasqr.xuimanager.domain.repository.SystemUpdateRepository
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
class SystemUpdateRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val scripts: ServerScripts,
) : SystemUpdateRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<SystemUpdateProgress?>>()

    override fun progress(serverId: Long): StateFlow<SystemUpdateProgress?> = flowFor(serverId)

    override fun clearProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun start(serverId: Long) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = SystemUpdateProgress(DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
                val result = scripts.run(
                    server = server,
                    script = "sysupdate.sh",
                    checkDomain = false,
                    timeoutMs = DeployConfig.SYSUPDATE_TIMEOUT_MS,
                    params = { RemoteJob.sysUpdateEnv(it.uid, it.gid) },
                    log = log,
                )
                flow.update { it?.copy(state = DeployState.SUCCEEDED, rebooting = result["REBOOT"] == "1") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                log("ERROR: $error")
                flow.update { it?.copy(state = DeployState.FAILED, error = error) }
            }
        }
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }
}
