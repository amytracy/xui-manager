package dev.autorasqr.xuimanager.data.ssh

import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import dev.autorasqr.xuimanager.domain.model.SshCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import javax.inject.Inject
import javax.inject.Singleton

data class SshTarget(
    val host: String,
    val port: Int,
    val user: String,
    val credentials: SshCredentials,
    val pin: HostKeyPin?,
)

@Singleton
class SshConnector @Inject constructor() {

    private val config by lazy { DefaultConfig() }

    /** Completes key exchange only far enough to see the host key; never authenticates. */
    suspend fun fetchHostKey(host: String, port: Int): HostKeyPin = withContext(Dispatchers.IO) {
        val verifier = PinningHostKeyVerifier(expected = null)
        val client = newClient(verifier)
        try {
            client.connect(host, port)
        } catch (e: Exception) {
            if (verifier.presented == null) throw AppException(e.toAppError(), e)
        } finally {
            client.disconnectQuietly()
        }
        verifier.presented ?: throw AppException(AppError.Unexpected("no host key"))
    }

    suspend fun <T> withSession(target: SshTarget, block: suspend (SshSession) -> T): T =
        withContext(Dispatchers.IO) {
            val verifier = PinningHostKeyVerifier(target.pin)
            val client = newClient(verifier)
            try {
                try {
                    client.connect(target.host, target.port)
                } catch (e: Exception) {
                    val presented = verifier.presented
                    if (presented != null && verifier.rejected) {
                        val error = target.pin?.let { AppError.HostKeyMismatch(it, presented) }
                            ?: AppError.HostKeyUnknown(presented)
                        throw AppException(error, e)
                    }
                    throw e
                }
                client.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
                authenticate(client, target)
                block(SshSession(client, (target.credentials as? SshCredentials.Password)?.password))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                throw e
            } catch (e: Exception) {
                throw AppException(e.toAppError(), e)
            } finally {
                client.disconnectQuietly()
            }
        }

    private fun authenticate(client: SSHClient, target: SshTarget) {
        when (val credentials = target.credentials) {
            is SshCredentials.Password -> {
                val finder = StaticPasswordFinder(credentials.password)
                client.auth(
                    target.user,
                    AuthPassword(finder),
                    AuthKeyboardInteractive(PasswordResponseProvider(finder)),
                )
            }
            is SshCredentials.PrivateKey -> client.authPublickey(target.user, loadKey(client, credentials))
        }
    }

    private fun loadKey(client: SSHClient, credentials: SshCredentials.PrivateKey): KeyProvider = try {
        val finder = credentials.passphrase?.takeIf { it.isNotEmpty() }?.let(::StaticPasswordFinder)
        client.loadKeys(credentials.pem.trim() + "\n", null, finder).also { it.private }
    } catch (e: Exception) {
        throw AppException(AppError.InvalidPrivateKey, e)
    }

    private fun newClient(verifier: PinningHostKeyVerifier) = SSHClient(config).apply {
        addHostKeyVerifier(verifier)
        connectTimeout = CONNECT_TIMEOUT_MS
        timeout = SOCKET_TIMEOUT_MS
    }

    private fun SSHClient.disconnectQuietly() {
        runCatching { if (isConnected) disconnect() }
        runCatching { close() }
    }

    private class StaticPasswordFinder(private val secret: String) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = secret.toCharArray()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 60_000
        const val KEEPALIVE_SECONDS = 15
    }
}
