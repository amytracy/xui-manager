package dev.autorasqr.xuimanager.data.ssh

import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.SshCredentials
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshTargets @Inject constructor(
    private val secrets: SecretStore,
) {
    fun credentials(serverId: Long, authType: SshAuthType): SshCredentials? = when (authType) {
        SshAuthType.PASSWORD -> secrets.get(SecretKeys.sshPassword(serverId))?.let(SshCredentials::Password)
        SshAuthType.PRIVATE_KEY -> secrets.get(SecretKeys.sshPrivateKey(serverId))?.let { pem ->
            SshCredentials.PrivateKey(pem, secrets.get(SecretKeys.sshPassphrase(serverId)))
        }
    }

    /** Target for a saved server. A missing host key pin surfaces as HostKeyUnknown on connect. */
    fun forServer(server: Server): SshTarget = SshTarget(
        host = server.host,
        port = server.sshPort,
        user = server.sshUser,
        credentials = credentials(server.id, server.authType) ?: throw AppException(AppError.SshAuthFailed),
        pin = server.hostKey,
    )
}
