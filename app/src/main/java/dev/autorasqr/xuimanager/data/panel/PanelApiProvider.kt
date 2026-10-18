package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.Server
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out one adapter per server and reuses it while the server's panel details and credentials stay the same,
 * so connections (and the v2 login session) are shared between screens. Must be called off the main thread:
 * reading credentials touches the Keystore.
 */
@Singleton
class PanelApiProvider @Inject constructor(
    private val serverDao: ServerDao,
    private val secrets: SecretStore,
    private val factory: PanelApiFactory,
) {
    private class Entry(val host: String, val panel: PanelInfo, val credentials: PanelCredentials, val api: PanelApi)

    private val cache = ConcurrentHashMap<Long, Entry>()

    suspend fun forServer(serverId: Long): Pair<Server, PanelApi> {
        val server = serverDao.get(serverId)?.toDomain() ?: throw AppException(AppError.Unexpected("server not found"))
        val panel = server.panel ?: throw AppException(AppError.PanelNotDeployed)
        val credentials = PanelCredentials(
            apiToken = secrets.get(SecretKeys.panelToken(serverId)),
            username = panel.username,
            password = secrets.get(SecretKeys.panelPassword(serverId)),
        )
        cache[serverId]?.let { entry ->
            if (entry.host == server.panelHost && entry.panel == panel && entry.credentials == credentials) return server to entry.api
        }
        val endpoint = PanelEndpoint(server.panelHost, panel.port, panel.webBasePath)
        val api = factory.create(panel.version, endpoint, credentials)
        cache[serverId] = Entry(server.panelHost, panel, credentials, api)
        return server to api
    }

    fun invalidate(serverId: Long) {
        cache.remove(serverId)
    }
}
