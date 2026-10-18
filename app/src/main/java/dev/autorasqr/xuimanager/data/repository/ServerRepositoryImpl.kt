package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.ServerEntity
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelEndpoint
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshTarget
import dev.autorasqr.xuimanager.data.ssh.SshTargets
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import dev.autorasqr.xuimanager.domain.model.PanelLogin
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.ServerDraft
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.SshCredentials
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val dao: ServerDao,
    private val secrets: SecretStore,
    private val ssh: SshConnector,
    private val targets: SshTargets,
    private val panels: PanelApiProvider,
) : ServerRepository {

    override fun observeServers(): Flow<List<Server>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeServer(id: Long): Flow<Server?> = dao.observe(id).map { it?.toDomain() }

    override suspend fun getServer(id: Long): Server? = dao.get(id)?.toDomain()

    override suspend fun fetchHostKey(host: String, port: Int): HostKeyPin = ssh.fetchHostKey(host.trim(), port)

    override suspend fun verifyLogin(draft: ServerDraft, pin: HostKeyPin) = withContext(Dispatchers.IO) {
        val credentials = credentialsFor(draft) ?: throw AppException(
            AppError.Validation(if (draft.authType == SshAuthType.PASSWORD) ValidationField.PASSWORD else ValidationField.PRIVATE_KEY),
        )
        val target = SshTarget(draft.host.trim(), draft.sshPort, draft.sshUser.trim(), credentials, pin)
        ssh.withSession(target) { session ->
            if (!session.execSh("id -u").ok) throw AppException(AppError.Unexpected("id -u failed"))
        }
    }

    override fun hasStoredSecret(serverId: Long, authType: SshAuthType): Boolean =
        targets.credentials(serverId, authType) != null

    override suspend fun save(draft: ServerDraft, pin: HostKeyPin?): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val host = draft.host.trim()
        val domain = draft.domain.trim().lowercase().trimEnd('.').ifEmpty { null }
        val id = if (draft.id == null) {
            dao.insert(
                ServerEntity(
                    name = draft.name.trim(),
                    host = host,
                    sshPort = draft.sshPort,
                    sshUser = draft.sshUser.trim(),
                    authType = draft.authType,
                    hostKeyAlgorithm = pin?.algorithm,
                    hostKeyFingerprint = pin?.fingerprint,
                    createdAt = now,
                    updatedAt = now,
                    panel = null,
                    domain = domain,
                    masterServerId = draft.masterServerId,
                ),
            )
        } else {
            val existing = dao.get(draft.id) ?: throw AppException(AppError.Unexpected("server not found"))
            val endpointChanged = existing.host != host || existing.sshPort != draft.sshPort
            val keptPin = pin ?: if (endpointChanged) null else existing.toDomain().hostKey
            dao.update(
                existing.copy(
                    name = draft.name.trim(),
                    host = host,
                    sshPort = draft.sshPort,
                    sshUser = draft.sshUser.trim(),
                    authType = draft.authType,
                    hostKeyAlgorithm = keptPin?.algorithm,
                    hostKeyFingerprint = keptPin?.fingerprint,
                    updatedAt = now,
                    domain = domain,
                ),
            )
            draft.id
        }
        storeSecrets(id, draft)
        id
    }

    override suspend fun panelCredentials(serverId: Long): PanelLogin? = withContext(Dispatchers.IO) {
        val server = dao.get(serverId)?.toDomain() ?: return@withContext null
        val panel = server.panel ?: return@withContext null
        PanelLogin(
            url = PanelEndpoint(server.panelHost, panel.port, panel.webBasePath).baseUrl().toString(),
            username = panel.username,
            password = secrets.get(SecretKeys.panelPassword(serverId)),
        )
    }

    override suspend fun pinHostKey(serverId: Long, pin: HostKeyPin) {
        dao.updateHostKey(serverId, pin.algorithm, pin.fingerprint, System.currentTimeMillis())
    }

    /**
     * Deleting a master also forgets the servers deployed as its nodes (they stay registered on its panel, which is
     * gone from the app anyway). Deleting a node server takes it off the master panel first, when that is reachable.
     */
    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.get(id)
        val master = entity?.masterServerId
        val nodeId = entity?.nodeId
        if (master != null && nodeId != null) {
            try {
                panels.forServer(master).second.deleteNode(nodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The master may be unreachable or the node already removed there; the local record goes regardless.
            }
            panels.invalidate(master)
        }
        for (node in dao.nodesOf(id)) {
            dao.delete(node.id)
            secrets.removeByPrefix(SecretKeys.serverPrefix(node.id))
            panels.invalidate(node.id)
        }
        dao.delete(id)
        secrets.removeByPrefix(SecretKeys.serverPrefix(id))
        panels.invalidate(id)
    }

    private fun credentialsFor(draft: ServerDraft): SshCredentials? = when (draft.authType) {
        SshAuthType.PASSWORD -> draft.password.takeIf { it.isNotEmpty() }?.let(SshCredentials::Password)
            ?: draft.id?.let { targets.credentials(it, SshAuthType.PASSWORD) }
        SshAuthType.PRIVATE_KEY -> if (draft.privateKey.isNotBlank()) {
            SshCredentials.PrivateKey(draft.privateKey.trim(), draft.passphrase.ifEmpty { null })
        } else {
            (draft.id?.let { targets.credentials(it, SshAuthType.PRIVATE_KEY) } as? SshCredentials.PrivateKey)
                ?.let { stored -> if (draft.passphrase.isNotEmpty()) stored.copy(passphrase = draft.passphrase) else stored }
        }
    }

    private fun storeSecrets(id: Long, draft: ServerDraft) {
        when (draft.authType) {
            SshAuthType.PASSWORD -> {
                if (draft.password.isNotEmpty()) secrets.put(SecretKeys.sshPassword(id), draft.password)
                secrets.remove(SecretKeys.sshPrivateKey(id))
                secrets.remove(SecretKeys.sshPassphrase(id))
            }
            SshAuthType.PRIVATE_KEY -> {
                if (draft.privateKey.isNotBlank()) {
                    secrets.put(SecretKeys.sshPrivateKey(id), draft.privateKey.trim())
                    secrets.remove(SecretKeys.sshPassphrase(id))
                }
                if (draft.passphrase.isNotEmpty()) secrets.put(SecretKeys.sshPassphrase(id), draft.passphrase)
                secrets.remove(SecretKeys.sshPassword(id))
            }
        }
    }
}
