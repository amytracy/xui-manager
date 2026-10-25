package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.db.toEmbedded
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.PanelProvisioner
import dev.autorasqr.xuimanager.data.deploy.RandomTokens
import dev.autorasqr.xuimanager.data.deploy.RemoteJob
import dev.autorasqr.xuimanager.data.deploy.ServerScripts
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelApiFactory
import dev.autorasqr.xuimanager.data.panel.PanelCredentials
import dev.autorasqr.xuimanager.data.panel.PanelEndpoint
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshTargets
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.PrecheckResult
import dev.autorasqr.xuimanager.domain.model.RestoreProgress
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.domain.repository.RestoreRepository
import dev.autorasqr.xuimanager.domain.usecase.HostValidator
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

/** Values restore.sh reports in result.env. */
internal data class RestoreResult(
    val panelPort: Int,
    val webBasePath: String,
    val panelVersion: String,
    /** Missing on 3x-ui 2.x, which has no API tokens. */
    val apiToken: String?,
    /** The name the panel certificate covers: where the app reaches the panel and subscriptions. */
    val certHost: String,
    val selfstealPort: Int?,
) {
    /** A certificate for a name rather than an address means the server has a domain. */
    val domain: String? get() = certHost.lowercase().takeIf { HostValidator.isHostname(it) && !HostValidator.isIpv4(it) }

    override fun toString() = "RestoreResult(panelPort=$panelPort, version=$panelVersion, host=$certHost)"

    companion object {
        fun parse(values: Map<String, String>): RestoreResult {
            fun required(key: String) = values[key]?.takeIf { it.isNotEmpty() }
                ?: throw AppException(AppError.Unexpected("restore: missing $key"))
            return RestoreResult(
                panelPort = required("PANEL_PORT").toIntOrNull() ?: throw AppException(AppError.Unexpected("restore: bad port")),
                webBasePath = values["WEB_BASE_PATH"].orEmpty().ifEmpty { "/" },
                panelVersion = PanelVersion.normalize(required("PANEL_VERSION")),
                apiToken = values["API_TOKEN"]?.takeIf { it.isNotEmpty() },
                certHost = required("CERT_HOST"),
                selfstealPort = values["SELFSTEAL_PORT"]?.toIntOrNull(),
            )
        }
    }
}

@Singleton
class RestoreRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val secrets: SecretStore,
    private val scripts: ServerScripts,
    private val random: RandomTokens,
    private val apiFactory: PanelApiFactory,
    private val provisioner: PanelProvisioner,
    private val nodes: NodeRepository,
    private val deployRepository: DeployRepository,
    private val ssh: SshConnector,
    private val targets: SshTargets,
) : RestoreRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<RestoreProgress?>>()

    override fun progress(serverId: Long): StateFlow<RestoreProgress?> = flowFor(serverId)

    override fun clearProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun start(serverId: Long) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        flow.value = RestoreProgress(DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                restore(serverId, log)
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

    /** Safe to run again after a failure: every run issues a new login and token and records them. */
    private suspend fun restore(serverId: Long, log: (String) -> Unit) {
        val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
        if (deployRepository.unfinishedJob(serverId) != null) throw AppException(AppError.DeployAlreadyRunning)
        val username = random.alphanumeric(10)
        val password = random.alphanumeric(24)
        var precheck: PrecheckResult? = null
        val values = scripts.run(
            server = server,
            script = "restore.sh",
            checkDomain = false,
            timeoutMs = RESTORE_TIMEOUT_MS,
            params = { result ->
                precheck = result
                RemoteJob.restoreEnv(result.uid, result.gid, server.domain ?: server.host)
            },
            log = log,
            extraFiles = mapOf("secrets.env" to RemoteJob.restoreSecretsEnv(username, password).toByteArray()),
        )
        val result = RestoreResult.parse(values)
        if (!PanelVersion.isSupported(result.panelVersion)) throw AppException(AppError.UnsupportedPanelVersion(result.panelVersion))
        if ((PanelVersion.major(result.panelVersion) ?: 0) >= 3 && result.apiToken == null) throw AppException(AppError.ApiTokenFailed)

        // The server now only accepts these: store them before anything else can fail.
        secrets.put(SecretKeys.panelPassword(serverId), password)
        if (result.apiToken != null) secrets.put(SecretKeys.panelToken(serverId), result.apiToken) else secrets.remove(SecretKeys.panelToken(serverId))

        val api = apiFactory.create(
            result.panelVersion,
            PanelEndpoint(result.certHost, result.panelPort, result.webBasePath),
            PanelCredentials(result.apiToken, username, password),
        )
        log("Connecting to the panel on ${result.certHost}:${result.panelPort}")
        try {
            provisioner.waitUntilReady(api)
        } catch (e: Exception) {
            if (e is CancellationException || e.toAppError() != AppError.PanelUnauthorized) throw e
            // Normally the panel takes the new login and token at once; a restart is the last resort, since it drops
            // every connected client for a few seconds.
            log("The panel does not accept the new login yet; restarting x-ui")
            val sudo = precheck?.sudo ?: throw e
            ssh.withSession(targets.forServer(server)) { it.execPrivileged("systemctl restart x-ui", sudo) }
            delay(4_000)
            provisioner.waitUntilReady(api)
        }
        val outcome = provisioner.describe(
            api = api,
            host = result.certHost,
            version = result.panelVersion,
            port = result.panelPort,
            webBasePath = result.webBasePath,
            username = username,
            selfstealPort = result.selfstealPort,
            log = log,
        )
        serverDao.get(serverId)?.let { entity ->
            serverDao.update(
                entity.copy(
                    panel = outcome.panel.toEmbedded(),
                    domain = entity.domain ?: result.domain,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        result.domain?.let { if (server.domain == null) log("Domain $it taken from the panel certificate") }
        result.selfstealPort?.let { log("Self-steal decoy site on 127.0.0.1:$it") }

        // The master reaches a node with the token that was just replaced, so it must get the new one; until then the
        // node shows as offline there, and a retry of the whole restore is what fixes it.
        val master = server.masterServerId ?: return
        log("Registering the panel on the main panel again with the new token")
        val nodeId = nodes.register(master, serverId)
        log("Registered as node #$nodeId")
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }

    private companion object {
        const val RESTORE_TIMEOUT_MS = 5 * 60_000L
    }
}
