package dev.autorasqr.xuimanager.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.autorasqr.xuimanager.data.db.DeployJobDao
import dev.autorasqr.xuimanager.data.db.DeployJobEntity
import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.DeployLauncher
import dev.autorasqr.xuimanager.data.deploy.DeployProgressRegistry
import dev.autorasqr.xuimanager.data.deploy.Fingerprints
import dev.autorasqr.xuimanager.data.deploy.PrecheckParser
import dev.autorasqr.xuimanager.data.deploy.RandomTokens
import dev.autorasqr.xuimanager.data.deploy.SitePageStore
import dev.autorasqr.xuimanager.data.deploy.StoredDeployParams
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshTargets
import dev.autorasqr.xuimanager.data.ssh.shQuote
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DEFAULT_FINGERPRINT
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployOptions
import dev.autorasqr.xuimanager.domain.model.DeployProgress
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.PrecheckResult
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.SudoMode
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.usecase.DomainCheck
import dev.autorasqr.xuimanager.domain.usecase.HostValidator
import dev.autorasqr.xuimanager.domain.usecase.InboundMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeployRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val jobDao: DeployJobDao,
    private val secrets: SecretStore,
    private val ssh: SshConnector,
    private val targets: SshTargets,
    private val registry: DeployProgressRegistry,
    private val random: RandomTokens,
    private val launcher: DeployLauncher,
    private val sitePages: SitePageStore,
    private val json: Json,
) : DeployRepository {

    override suspend fun precheck(serverId: Long, checkDomain: Boolean): PrecheckResult = withContext(Dispatchers.IO) {
        val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
        val domain = server.domain?.takeIf { checkDomain }
        val prefix = domain?.let { "DOMAIN=${shQuote(it)}\n" }.orEmpty()
        val script = prefix.toByteArray() + context.assets.open("deploy/precheck.sh").use { it.readBytes() }
        val result = ssh.withSession(targets.forServer(server)) { session ->
            PrecheckParser.parse(session.exec("sh -s", stdin = script).stdoutText)
        }
        if (domain != null) {
            val resolved = result.domainIps.orEmpty()
            if (!DomainCheck.pointsHere(resolved, result.serverIps, server.host)) {
                val own = (result.serverIps + listOfNotNull(server.host.takeIf(HostValidator::isIpv4))).distinct()
                throw AppException(AppError.DomainDnsMismatch(domain, resolved, own))
            }
        }
        result
    }

    override suspend fun startDeploy(serverId: Long, options: DeployOptions, precheck: PrecheckResult): Long =
        withContext(Dispatchers.IO) { createJob(serverId, options, precheck) }

    private suspend fun createJob(serverId: Long, options: DeployOptions, precheck: PrecheckResult): Long {
        val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
        jobDao.latestInState(serverId, DeployState.RUNNING)?.let { throw AppException(AppError.DeployAlreadyRunning) }
        when {
            !precheck.systemd -> throw AppException(AppError.UnsupportedSystem)
            precheck.sudo == SudoMode.NONE -> throw AppException(AppError.NoRootAccess)
            precheck.sudo == SudoMode.PASSWORD && server.authType != SshAuthType.PASSWORD ->
                throw AppException(AppError.SudoPasswordRequired)
            precheck.existingInstalled && options.existingAction == null ->
                throw AppException(AppError.AlreadyInstalled(precheck.existingVersion))
            options.existingAction == ExistingInstallAction.ADOPT && precheck.existingInstalled &&
                !PanelVersion.isSupported(precheck.existingVersion.orEmpty()) ->
                throw AppException(AppError.UnsupportedPanelVersion(precheck.existingVersion ?: "?"))
        }
        val manual = if (options.mode == DeployMode.MANUAL) {
            val config = options.manual ?: throw AppException(AppError.Validation(ValidationField.TRANSPORT))
            InboundMatrix.validate(config, server.sshPort)?.let { throw AppException(AppError.Validation(it)) }
            config.copy(sni = config.sni?.trim()?.lowercase())
        } else {
            null
        }

        val panelPort = random.port()
        var subPort = random.port()
        while (subPort == panelPort) subPort = random.port()
        val params = StoredDeployParams(
            mode = options.mode,
            sni = options.sni,
            existingAction = options.existingAction.takeIf { precheck.existingInstalled },
            existingInstalled = precheck.existingInstalled,
            sudo = precheck.sudo,
            uid = precheck.uid,
            gid = precheck.gid,
            home = precheck.home,
            sslMode = when {
                HostValidator.isIpv4(server.deployHost) -> "ip"
                HostValidator.isHostname(server.deployHost) -> "domain"
                else -> "none"
            },
            panelUsername = random.alphanumeric(10),
            panelPort = panelPort,
            subPort = subPort,
            inboundPort = manual?.port ?: DeployConfig.DEFAULT_INBOUND_PORT,
            // A port the user typed must be honoured or fail; one-click may move off 443.
            inboundPortStrict = manual != null,
            manual = manual,
            serverHost = server.deployHost,
            selfSteal = options.selfSteal && server.domain != null && options.mode == DeployMode.ONE_CLICK &&
                options.existingAction != ExistingInstallAction.ADOPT,
            clientRouting = options.clientRouting && options.existingAction != ExistingInstallAction.ADOPT,
            fingerprint = options.fingerprint.takeIf { it in Fingerprints.all } ?: DEFAULT_FINGERPRINT,
        )
        val jobId = jobDao.insert(
            DeployJobEntity(
                serverId = serverId,
                mode = options.mode,
                paramsJson = json.encodeToString(StoredDeployParams.serializer(), params),
                remoteDir = "",
                state = DeployState.RUNNING,
                stage = null,
                errorCode = null,
                warnings = "",
                startedAt = System.currentTimeMillis(),
                launchedAt = null,
                finishedAt = null,
            ),
        )
        secrets.put(SecretKeys.jobPanelPassword(jobId), random.alphanumeric(24))
        secrets.put(SecretKeys.jobWebBasePath(jobId), random.alphanumeric(18))
        if (params.selfSteal) options.sitePage?.let { sitePages.put(jobId, it) }
        launcher.start(jobId)
        return jobId
    }

    override suspend fun resume(jobId: Long) {
        if (!registry.isAttached(jobId)) launcher.start(jobId)
    }

    override fun observeProgress(jobId: Long): Flow<DeployProgress?> = registry.flow(jobId)

    override suspend fun unfinishedJob(serverId: Long): Long? = jobDao.latestInState(serverId, DeployState.RUNNING)?.id

    override fun isAttached(jobId: Long): Boolean = registry.isAttached(jobId)
}
