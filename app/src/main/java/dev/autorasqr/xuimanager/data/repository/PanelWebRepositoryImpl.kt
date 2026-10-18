package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelEndpoint
import dev.autorasqr.xuimanager.data.panel.PanelWebLogin
import dev.autorasqr.xuimanager.data.panel.net.MemoryCookieJar
import dev.autorasqr.xuimanager.data.panel.net.PanelHttpClientFactory
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.PanelWebSession
import dev.autorasqr.xuimanager.domain.repository.PanelWebRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PanelWebRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val secrets: SecretStore,
    private val httpClients: PanelHttpClientFactory,
    private val json: Json,
) : PanelWebRepository {

    override suspend fun openSession(serverId: Long): PanelWebSession = ioMapped {
        val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
        val panel = server.panel ?: throw AppException(AppError.PanelNotDeployed)
        val base = PanelEndpoint(server.panelHost, panel.port, panel.webBasePath).baseUrl()
        val cookies = MemoryCookieJar()
        val client = httpClients.create(bearerToken = null, cookieJar = cookies)

        val password = secrets.get(SecretKeys.panelPassword(serverId))
        val loginError = if (password == null) {
            AppError.PanelUnauthorized
        } else {
            try {
                PanelWebLogin.login(client, base, panel.version, panel.username, password, json)
                null
            } catch (e: Exception) {
                when (val error = e.toAppError()) {
                    // An untrusted certificate is a hard stop: the WebView would refuse it anyway.
                    AppError.PanelCertificateInvalid -> throw AppException(error, e)
                    else -> error
                }
            }
        }
        PanelWebSession(
            serverName = server.name,
            origin = base.toString(),
            startUrl = if (loginError == null) base.resolve("panel/")!!.toString() else base.toString(),
            cookies = if (loginError == null) cookies.loadForRequest(base).map { it.toString() } else emptyList(),
            loginError = loginError,
        )
    }
}
