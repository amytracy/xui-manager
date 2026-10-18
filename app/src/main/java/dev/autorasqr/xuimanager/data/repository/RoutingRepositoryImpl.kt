package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.RemoteJob
import dev.autorasqr.xuimanager.data.deploy.ServerScripts
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.routing.RoscomVpn
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.ClientRouting
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.RoutingJobProgress
import dev.autorasqr.xuimanager.domain.model.RoutingState
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.RoutingRepository
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
class RoutingRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
    private val serverDao: ServerDao,
    private val scripts: ServerScripts,
) : RoutingRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<RoutingJobProgress?>>()

    override suspend fun load(serverId: Long): RoutingState = ioMapped {
        val api = panels.forServer(serverId).second
        if (!api.capabilities.routing) return@ioMapped RoutingState(supported = false, clientRouting = ClientRouting.OFF, serverBlocksRu = false)
        RoutingState(
            supported = true,
            clientRouting = RoscomVpn.profileOf(api.settings()),
            serverBlocksRu = RoscomVpn.hasServerBlock(api.xrayTemplate().config),
        )
    }

    override suspend fun setClientRouting(serverId: Long, profile: ClientRouting) = ioMapped {
        val api = panels.forServer(serverId).second
        requireRouting(api)
        api.patchSettings(RoscomVpn.subscriptionSettings(profile))
        // The subscription server reads its settings when the panel starts.
        api.restartPanel()
        waitForPanel(api)
    }

    override fun serverBlockProgress(serverId: Long): StateFlow<RoutingJobProgress?> = flowFor(serverId)

    override fun clearServerBlockProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun setServerBlock(serverId: Long, enabled: Boolean) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = RoutingJobProgress(enabled, DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
                val api = panels.forServer(serverId).second
                requireRouting(api)
                if (enabled) {
                    runGeodata(server, "install", log)
                    updateRules(api, enabled = true, log)
                } else {
                    // Rules first: Xray must never reference files that are already gone.
                    updateRules(api, enabled = false, log)
                    runGeodata(server, "remove", log)
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

    private suspend fun runGeodata(server: Server, action: String, log: (String) -> Unit) {
        scripts.run(
            server = server,
            script = "geodata.sh",
            checkDomain = false,
            timeoutMs = DeployConfig.GEODATA_TIMEOUT_MS,
            params = { RemoteJob.geodataEnv(action, it.uid, it.gid) },
            log = log,
        )
    }

    private suspend fun updateRules(api: PanelApi, enabled: Boolean, log: (String) -> Unit) {
        val template = api.xrayTemplate()
        val updated = RoscomVpn.withServerBlock(template.config, enabled)
        if (updated == template.config) {
            log("Xray rules are already " + if (enabled) "in place" else "removed")
            return
        }
        api.saveXrayTemplate(template.copy(config = updated))
        log(if (enabled) "Xray now refuses Russian destinations (RoscomVPN geo files)" else "Xray rules for Russian destinations removed")
    }

    private fun requireRouting(api: PanelApi) {
        if (!api.capabilities.routing) throw AppException(AppError.UnsupportedPanelVersion("2.x"))
    }

    private suspend fun waitForPanel(api: PanelApi) {
        delay(3_000)
        val deadline = System.currentTimeMillis() + 60_000
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                api.status()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
            delay(2_000)
        }
        throw last ?: AppException(AppError.PanelStartFailed)
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }
}
