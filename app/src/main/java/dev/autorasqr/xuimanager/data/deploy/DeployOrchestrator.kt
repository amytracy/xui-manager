package dev.autorasqr.xuimanager.data.deploy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.autorasqr.xuimanager.data.db.DeployJobDao
import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.db.toEmbedded
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelCredentials
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshSession
import dev.autorasqr.xuimanager.data.ssh.SshTarget
import dev.autorasqr.xuimanager.data.ssh.SshTargets
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployProgress
import dev.autorasqr.xuimanager.domain.model.DeployStage
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.DeployWarning
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives one deploy job: uploads and launches run.sh detached on the server, tails its log until it exits,
 * then finishes the panel setup over the API. Safe to call again for a job that is already running remotely
 * (after process death): it skips the launch and re-attaches to the log.
 */
@Singleton
class DeployOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val jobDao: DeployJobDao,
    private val serverDao: ServerDao,
    private val secrets: SecretStore,
    private val ssh: SshConnector,
    private val targets: SshTargets,
    private val provisioner: PanelProvisioner,
    private val runner: RemoteScriptRunner,
    private val registry: DeployProgressRegistry,
    private val nodes: NodeRepository,
    private val json: Json,
) {
    suspend fun run(jobId: Long) {
        if (!registry.tryAttach(jobId)) return
        try {
            execute(jobId)
        } finally {
            registry.detach(jobId)
        }
    }

    private suspend fun execute(jobId: Long) {
        val job = jobDao.get(jobId) ?: return
        if (job.state != DeployState.RUNNING) return
        registry.set(
            DeployProgress(
                jobId = jobId,
                serverId = job.serverId,
                state = DeployState.RUNNING,
                stage = job.stage?.let(DeployStage::fromRemote) ?: DeployStage.CONNECT,
                log = emptyList(),
                error = null,
                warnings = emptySet(),
            ),
        )
        var target: SshTarget? = null
        var jobDir: String? = null
        try {
            val server = serverDao.get(job.serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server deleted"))
            val params = json.decodeFromString(StoredDeployParams.serializer(), job.paramsJson)
            val skipped = buildSet {
                if (params.adopt) addAll(listOf(DeployStage.DOWNLOAD, DeployStage.INSTALL, DeployStage.KEYS))
                if (params.adopt || !params.selfSteal) add(DeployStage.SELFSTEAL)
            }
            registry.update(jobId) { it.copy(skippedStages = skipped) }
            target = targets.forServer(server)
            jobDir = RemoteJob.jobDir(params.home, jobId)

            if (job.launchedAt == null) {
                setStage(jobId, DeployStage.UPLOAD)
                ssh.withSession(target) { session ->
                    // The app may have died after starting run.sh but before recording it; never start a second one.
                    if (session.execSh(RemoteJob.runnerAliveCommand(jobDir)).ok) {
                        log(jobId, "Re-attached to the running deployment")
                    } else {
                        launch(session, jobId, jobDir, params, params.serverHost ?: server.host)
                    }
                }
                jobDao.markLaunched(jobId, jobDir, System.currentTimeMillis())
            } else {
                log(jobId, "Re-attached to the running deployment")
            }

            val exitCode = followRemote(jobId, target, jobDir, job.launchedAt ?: System.currentTimeMillis())
            if (exitCode != "0") throw AppException(DeployExitCodes.toAppError(exitCode))

            setStage(jobId, DeployStage.PANEL_SETUP)
            val (resultText, x25519Text) = ssh.withSession(target) { session ->
                val output = session.execSh(RemoteJob.readResultCommand(jobDir))
                if (!output.ok) throw AppException(AppError.Unexpected("result.env is missing"))
                val text = output.stdoutText
                text.substringBefore(RemoteJob.RESULT_SEPARATOR) to text.substringAfter(RemoteJob.RESULT_SEPARATOR, "")
            }
            val result = ResultEnvParser.parse(resultText)
            val keys = X25519Parser.parse(x25519Text)
            val password = secrets.get(SecretKeys.jobPanelPassword(jobId))
            // An adopted panel keeps its own path, which run.sh reports; otherwise it is the one the app generated.
            val basePath = result.webBasePath ?: secrets.get(SecretKeys.jobWebBasePath(jobId))
            if (password == null || basePath == null) throw AppException(AppError.Unexpected("job secrets are missing"))
            // 3.x is reached only with an API token; say so instead of a confusing "credentials rejected" later.
            if ((PanelVersion.major(PanelVersion.normalize(result.panelVersion)) ?: 0) >= 3 && result.apiToken == null) {
                throw AppException(AppError.ApiTokenFailed)
            }

            // Persist what the server now uses before touching the panel API, so a failure below still leaves
            // the app able to reach the panel.
            secrets.put(SecretKeys.panelPassword(server.id), password)
            if (result.apiToken != null) {
                secrets.put(SecretKeys.panelToken(server.id), result.apiToken)
            } else {
                secrets.remove(SecretKeys.panelToken(server.id))
            }

            val credentials = PanelCredentials(result.apiToken, params.panelUsername, password)
            val outcome = provisioner.provision(params.serverHost ?: server.host, basePath, params, result, credentials, keys) { line ->
                log(jobId, line)
            }

            serverDao.get(server.id)?.let { entity ->
                serverDao.update(entity.copy(panel = outcome.panel.toEmbedded(), updatedAt = System.currentTimeMillis()))
            }
            runCatching { ssh.withSession(target) { it.execSh(RemoteJob.cleanupCommand(jobDir)) } }
            secrets.removeByPrefix(SecretKeys.jobPrefix(jobId))

            val warnings = outcome.warnings + listOfNotNull(server.masterServerId?.let { registerNode(jobId, it, server.id) })
            jobDao.finish(jobId, DeployState.SUCCEEDED, null, warnings.joinToString(",") { it.name }, System.currentTimeMillis())
            registry.update(jobId) {
                it.copy(state = DeployState.SUCCEEDED, stage = DeployStage.DONE, warnings = warnings)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.toAppError()
            withContext(NonCancellable) {
                jobDao.finish(jobId, DeployState.FAILED, error.toString().take(200), "", System.currentTimeMillis())
                cleanupAfterFailure(jobId, target, jobDir)
            }
            registry.update(jobId) { it.copy(state = DeployState.FAILED, error = error) }
        }
    }

    /**
     * The panel is up; hand it to the master panel as a node. A failure here does not undo the deploy: the node can be
     * registered again from the master's node list. Returns the warning to show, if any.
     */
    private suspend fun registerNode(jobId: Long, masterId: Long, serverId: Long): DeployWarning? {
        log(jobId, "Registering the panel as a node of the main panel")
        return try {
            val nodeId = nodes.register(masterId, serverId)
            log(jobId, "Registered as node #$nodeId")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(jobId, "Could not register the node: ${e.toAppError()}")
            DeployWarning.NODE_NOT_REGISTERED
        }
    }

    /** A failed run may leave the Reality private key or the API token on the server; remove them and the job secrets. */
    private suspend fun cleanupAfterFailure(jobId: Long, target: SshTarget?, jobDir: String?) {
        secrets.removeByPrefix(SecretKeys.jobPrefix(jobId))
        if (target == null || jobDir == null || jobDao.get(jobId)?.launchedAt == null) return
        try {
            ssh.withSession(target) { it.execSh(RemoteJob.cleanupCommand(jobDir)) }
        } catch (e: Exception) {
            log(jobId, "Could not remove temporary files from the server: ${e.toAppError()}")
        }
    }

    private suspend fun launch(session: SshSession, jobId: Long, jobDir: String, params: StoredDeployParams, host: String) {
        val password = secrets.get(SecretKeys.jobPanelPassword(jobId))
        val basePath = secrets.get(SecretKeys.jobWebBasePath(jobId))
        if (password == null || basePath == null) throw AppException(AppError.Unexpected("job secrets are missing"))
        val files = DeployConfig.SCRIPTS.associateWith { name -> context.assets.open("deploy/$name").use { it.readBytes() } } +
            mapOf(
                "params.env" to RemoteJob.paramsEnv(params, host).toByteArray(),
                "secrets.env" to RemoteJob.secretsEnv(params.panelUsername, password, basePath).toByteArray(),
            )
        runner.launch(session, jobDir, files, "run.sh", params.sudo)
        log(jobId, "Deployment started on the server")
    }

    /** Tails the remote log until run.sh writes its exit code. Survives dropped connections. */
    private suspend fun followRemote(jobId: Long, target: SshTarget, jobDir: String, launchedAt: Long): String =
        runner.follow(
            target = target,
            jobDir = jobDir,
            launchedAt = launchedAt,
            timeoutMs = DeployConfig.DEPLOY_TIMEOUT_MS,
            onLines = { registry.appendLog(jobId, it) },
            onStage = { name -> DeployStage.fromRemote(name)?.let { setStage(jobId, it) } },
            onReconnect = { log(jobId, it) },
        )

    private suspend fun setStage(jobId: Long, stage: DeployStage) {
        if (registry.flow(jobId).value?.stage == stage) return
        jobDao.updateStage(jobId, stage.name)
        registry.update(jobId) { it.copy(stage = stage) }
    }

    private fun log(jobId: Long, line: String) = registry.appendLog(jobId, listOf("• $line"))
}
