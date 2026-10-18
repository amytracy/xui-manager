package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.data.panel.net.MemoryCookieJar
import dev.autorasqr.xuimanager.data.panel.net.PanelHttpClientFactory
import dev.autorasqr.xuimanager.data.panel.v2.V2PanelApi
import dev.autorasqr.xuimanager.data.panel.v2.V2PanelService
import dev.autorasqr.xuimanager.data.panel.v3.V3PanelApi
import dev.autorasqr.xuimanager.data.panel.v3.V3PanelService
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PanelApiFactory @Inject constructor(
    private val httpClients: PanelHttpClientFactory,
    private val json: Json,
) {
    /**
     * v3.x → token-authenticated adapter; v2.4–v2.9 → session adapter (server routes moved in 2.7.0).
     * Anything older is refused: its API differs too much to be worth guessing.
     */
    fun create(version: String, endpoint: PanelEndpoint, credentials: PanelCredentials): PanelApi {
        val parts = PanelVersion.parse(version)
        val major = parts.getOrNull(0)
        val minor = parts.getOrNull(1) ?: 0
        return when {
            major != null && major >= 3 -> {
                val token = credentials.apiToken ?: throw AppException(AppError.PanelUnauthorized)
                V3PanelApi(retrofit(endpoint, httpClients.create(token)).create(V3PanelService::class.java), json)
            }
            major == 2 && minor >= 4 -> {
                val password = credentials.password ?: throw AppException(AppError.PanelUnauthorized)
                val cookies = MemoryCookieJar()
                val client = httpClients.create(bearerToken = null, cookieJar = cookies)
                V2PanelApi(
                    service = retrofit(endpoint, client).create(V2PanelService::class.java),
                    json = json,
                    cookies = cookies,
                    username = credentials.username,
                    password = password,
                    modernServerRoutes = minor >= 7,
                    panelVersion = PanelVersion.normalize(version),
                )
            }
            else -> throw AppException(AppError.UnsupportedPanelVersion(version))
        }
    }

    private fun retrofit(endpoint: PanelEndpoint, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(endpoint.baseUrl())
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}

object PanelVersion {
    /** "v3.10.2", "3.10.2" or "3.10.2-beta" → [3, 10, 2]. */
    fun parse(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { part -> part.takeWhile(Char::isDigit) }
            .takeWhile { it.isNotEmpty() }
            .map(String::toInt)

    fun major(version: String): Int? = parse(version).firstOrNull()

    fun normalize(version: String): String {
        val parts = parse(version)
        return if (parts.isEmpty()) version.trim() else "v" + parts.joinToString(".")
    }

    /** True for versions this app has an adapter for (2.4 and newer). */
    fun isSupported(version: String): Boolean {
        val parts = parse(version)
        val major = parts.getOrNull(0) ?: return false
        return major >= 3 || (major == 2 && (parts.getOrNull(1) ?: 0) >= 4)
    }
}
