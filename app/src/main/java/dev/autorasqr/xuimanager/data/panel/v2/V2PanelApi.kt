package dev.autorasqr.xuimanager.data.panel.v2

import dev.autorasqr.xuimanager.data.panel.ClientChanges
import dev.autorasqr.xuimanager.data.panel.InboundJson
import dev.autorasqr.xuimanager.data.panel.InboundSpec
import dev.autorasqr.xuimanager.data.panel.NewClient
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelCapabilities
import dev.autorasqr.xuimanager.data.panel.PanelClient
import dev.autorasqr.xuimanager.data.panel.PanelInbound
import dev.autorasqr.xuimanager.data.panel.PanelJson
import dev.autorasqr.xuimanager.data.panel.PanelJson.array
import dev.autorasqr.xuimanager.data.panel.PanelJson.bool
import dev.autorasqr.xuimanager.data.panel.PanelJson.int
import dev.autorasqr.xuimanager.data.panel.PanelJson.long
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import dev.autorasqr.xuimanager.data.panel.PanelStatus
import dev.autorasqr.xuimanager.data.panel.RealityTargetVerdict
import dev.autorasqr.xuimanager.data.panel.SubscriptionSettings
import dev.autorasqr.xuimanager.data.panel.SubscriptionUpdate
import dev.autorasqr.xuimanager.data.panel.X25519Keys
import dev.autorasqr.xuimanager.data.panel.XrayTemplate
import dev.autorasqr.xuimanager.data.panel.net.MemoryCookieJar
import dev.autorasqr.xuimanager.data.panel.v3.V3PanelApi
import dev.autorasqr.xuimanager.data.panel.v3.V3Status
import dev.autorasqr.xuimanager.data.panel.v3.V3X25519
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Adapter for 3x-ui 2.4–2.9. There are no API tokens: the adapter logs in with the panel credentials and keeps
 * the session cookie in memory, logging in again once if a request comes back unauthenticated.
 * Clients live inside each inbound's settings JSON, so edits read that JSON, change the client in place and send
 * it back, which keeps fields this app does not know about.
 */
internal class V2PanelApi(
    private val service: V2PanelService,
    private val json: Json,
    private val cookies: MemoryCookieJar,
    private val username: String,
    private val password: String,
    /** 2.7.0 and newer serve server routes under /panel/api/server. */
    private val modernServerRoutes: Boolean,
    private val panelVersion: String,
) : PanelApi {

    override val capabilities = PanelCapabilities(multiInboundClients = false, realityScan = false, xrayInstall = true)

    private val sessionLock = Mutex()
    @Volatile private var loggedIn = false
    private val random = SecureRandom()

    override suspend fun status(): PanelStatus {
        val dto = PanelJson.decode<V3Status>(json, request { if (modernServerRoutes) service.status() else service.statusLegacy() })
        return PanelStatus(
            cpuPercent = dto.cpu,
            memUsed = dto.mem.current,
            memTotal = dto.mem.total,
            uptimeSeconds = dto.uptime,
            loads = dto.loads,
            xrayState = dto.xray.state,
            xrayVersion = dto.xray.version,
            xrayError = dto.xray.errorMsg,
            panelVersion = dto.panelVersion.ifEmpty { panelVersion },
        )
    }

    override suspend fun listClients(): List<PanelClient> = inbounds().flatMap { inbound ->
        inbound.clients.map { client ->
            val email = client.string("email").orEmpty()
            val stats = inbound.stats[email]
            PanelClient(
                email = email,
                enabled = client.bool("enable") ?: true,
                subId = client.string("subId").orEmpty(),
                totalBytes = client.long("totalGB") ?: 0,
                expiryTime = client.long("expiryTime") ?: 0,
                up = stats?.long("up") ?: 0,
                down = stats?.long("down") ?: 0,
                inboundIds = listOf(inbound.id),
                comment = client.string("comment").orEmpty(),
            )
        }
    }

    override suspend fun onlineEmails(): Set<String> =
        (request { service.onlines() } as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()

    /** 2.x does not generate client secrets, so the UUID / password / SS key is created here. */
    override suspend fun addClient(client: NewClient) {
        val inboundId = client.inboundIds.singleOrNull()
            ?: throw AppException(AppError.PanelError("3x-ui 2.x puts a client in exactly one inbound"))
        val inbound = inbounds().firstOrNull { it.id == inboundId } ?: throw AppException(AppError.NoInbound)
        val entry = JsonObject(
            identityFor(inbound) + mapOf(
                "flow" to JsonPrimitive(if (inbound.summary.supportsVision) client.flow else ""),
                "email" to JsonPrimitive(client.email),
                "limitIp" to JsonPrimitive(0),
                "totalGB" to JsonPrimitive(client.totalBytes),
                "expiryTime" to JsonPrimitive(client.expiryTime),
                "enable" to JsonPrimitive(client.enabled),
                "tgId" to JsonPrimitive(0),
                "subId" to JsonPrimitive(client.subId),
                "comment" to JsonPrimitive(""),
                "reset" to JsonPrimitive(0),
            ),
        )
        request { service.addClient(inbound.id, settingsWith(entry)) }
    }

    override suspend fun updateClient(email: String, changes: ClientChanges) {
        val (inbound, current) = locate(email)
        val updated = JsonObject(
            current + mapOf(
                "email" to JsonPrimitive(changes.email),
                "subId" to JsonPrimitive(changes.subId),
                "totalGB" to JsonPrimitive(changes.totalBytes),
                "expiryTime" to JsonPrimitive(changes.expiryTime),
                "enable" to JsonPrimitive(changes.enabled),
            ),
        )
        request { service.updateClient(clientId(inbound, current), inbound.id, settingsWith(updated)) }
    }

    override suspend fun deleteClient(email: String) {
        val (inbound, current) = locate(email)
        request { service.deleteClient(inbound.id, clientId(inbound, current)) }
    }

    override suspend fun setClientEnabled(email: String, enabled: Boolean) {
        val (inbound, current) = locate(email)
        val updated = JsonObject(current + ("enable" to JsonPrimitive(enabled)))
        request { service.updateClient(clientId(inbound, current), inbound.id, settingsWith(updated)) }
    }

    override suspend fun resetClientTraffic(email: String) {
        val (inbound, _) = locate(email)
        request { service.resetClientTraffic(inbound.id, email) }
    }

    override suspend fun listInbounds(): List<PanelInbound> = inbounds().map { it.summary }

    /** 2.x stores settings, streamSettings and sniffing as JSON strings. */
    override suspend fun addInbound(spec: InboundSpec): Int {
        val fields = mapOf(
            "up" to "0",
            "down" to "0",
            "total" to "0",
            "remark" to spec.remark,
            "enable" to "true",
            "expiryTime" to "0",
            "listen" to "",
            "port" to spec.port.toString(),
            "protocol" to InboundJson.protocolName(spec.protocol),
            "settings" to InboundJson.settings(spec).toString(),
            "streamSettings" to InboundJson.streamSettings(spec).toString(),
            "sniffing" to InboundJson.sniffing().toString(),
        )
        val created = PanelJson.objectOrNull(json, request { service.addInbound(fields) })
        return created?.int("id")
            ?: listInbounds().firstOrNull { it.remark == spec.remark && it.port == spec.port }?.id
            ?: throw AppException(AppError.PanelError("inbound was not created"))
    }

    override suspend fun getInbound(id: Int): JsonObject =
        PanelJson.objectOrNull(json, request { service.inbound(id) })?.let { InboundJson.normalize(json, it) }
            ?: throw AppException(AppError.PanelError("inbound $id not found"))

    /** 2.x binds a form with every column, settings included, so the full inbound is sent back as text fields. */
    override suspend fun updateInbound(id: Int, inbound: JsonObject) {
        val fields = buildMap {
            for ((key, value) in inbound) {
                if (key in SKIPPED_ON_UPDATE || value is JsonNull) continue
                if (value is JsonArray) continue
                put(key, InboundJson.asText(value))
            }
        }
        request { service.updateInbound(id, fields) }
    }

    override suspend fun deleteInbound(id: Int) {
        request { service.deleteInbound(id) }
    }

    override suspend fun setInboundEnabled(id: Int, enabled: Boolean) {
        val current = getInbound(id)
        updateInbound(id, JsonObject(current + ("enable" to JsonPrimitive(enabled))))
    }

    override suspend fun subscriptionSettings(): SubscriptionSettings = V3PanelApi.subscriptionFrom(allSettings())

    override suspend fun updateSubscriptionSettings(update: SubscriptionUpdate) {
        request { service.updateSettings(V3PanelApi.patchSubscription(allSettings(), update)) }
    }

    override suspend fun settings(): JsonObject = allSettings()

    override suspend fun patchSettings(changes: Map<String, JsonElement>) {
        request { service.updateSettings(JsonObject(allSettings() + changes)) }
    }

    override suspend fun xrayTemplate(): XrayTemplate = throw AppException(AppError.PanelError("needs 3x-ui v3"))

    override suspend fun saveXrayTemplate(template: XrayTemplate) = throw AppException(AppError.PanelError("needs 3x-ui v3"))

    override suspend fun restartPanel() {
        request { service.restartPanel() }
    }

    override suspend fun restartXray() {
        request { if (modernServerRoutes) service.restartXray() else service.restartXrayLegacy() }
    }

    override suspend fun xrayVersions(): List<String> = PanelJson.decode<List<String>?>(
        json,
        request { if (modernServerRoutes) service.xrayVersions() else service.xrayVersionsLegacy() },
    ).orEmpty()

    override suspend fun installXray(version: String) {
        request { if (modernServerRoutes) service.installXray(version) else service.installXrayLegacy(version) }
    }

    override suspend fun newX25519(): X25519Keys {
        val dto = PanelJson.decode<V3X25519>(json, request { if (modernServerRoutes) service.newX25519() else service.newX25519Legacy() })
        if (dto.privateKey.isBlank() || dto.publicKey.isBlank()) throw AppException(AppError.PanelError("empty x25519 keypair"))
        return X25519Keys(dto.privateKey, dto.publicKey)
    }

    override suspend fun scanRealityTargets(candidates: List<String>): List<RealityTargetVerdict> = emptyList()

    private class Inbound(
        val summary: PanelInbound,
        val settings: JsonObject,
        val clients: List<JsonObject>,
        val stats: Map<String, JsonObject>,
    ) {
        val id: Int get() = summary.id
    }

    private suspend fun inbounds(): List<Inbound> =
        (request { service.inbounds() } as? JsonArray).orEmpty().mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            val settings = PanelJson.objectOrNull(json, raw["settings"]) ?: JsonObject(emptyMap())
            Inbound(
                summary = InboundJson.summary(json, raw) ?: return@mapNotNull null,
                settings = settings,
                clients = settings.array("clients").orEmpty().mapNotNull { it as? JsonObject },
                stats = raw.array("clientStats").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .associateBy { it.string("email").orEmpty() },
            )
        }

    private suspend fun locate(email: String): Pair<Inbound, JsonObject> {
        for (inbound in inbounds()) {
            inbound.clients.firstOrNull { it.string("email") == email }?.let { return inbound to it }
        }
        throw AppException(AppError.PanelError("client $email not found"))
    }

    /** The key 2.x uses to address a client: Trojan password, Shadowsocks email, otherwise the UUID. */
    private fun clientId(inbound: Inbound, client: JsonObject): String = when (inbound.summary.protocol) {
        "trojan" -> client.string("password")
        "shadowsocks" -> client.string("email")
        "hysteria", "hysteria2" -> client.string("auth")
        else -> client.string("id")
    }?.takeIf { it.isNotEmpty() } ?: throw AppException(AppError.PanelError("client has no id"))

    private fun identityFor(inbound: Inbound): Map<String, JsonElement> = when (inbound.summary.protocol) {
        "vless" -> mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()))
        "vmess" -> mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()), "security" to JsonPrimitive("auto"))
        "trojan" -> mapOf("password" to JsonPrimitive(randomToken(16)))
        "shadowsocks" -> {
            val method = inbound.settings.string("method").orEmpty()
            if (method.startsWith("2022")) {
                val keySize = if (method.contains("aes-128")) 16 else 32
                mapOf("method" to JsonPrimitive(""), "password" to JsonPrimitive(randomKey(keySize)))
            } else {
                mapOf("method" to JsonPrimitive(method), "password" to JsonPrimitive(randomToken(16)))
            }
        }
        else -> throw AppException(AppError.PanelError("clients of ${inbound.summary.protocol} inbounds are not supported"))
    }

    private fun settingsWith(client: JsonObject): String =
        buildJsonObject { put("clients", JsonArray(listOf(client))) }.toString()

    private fun randomKey(bytes: Int): String = Base64.getEncoder().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun randomToken(length: Int): String = buildString(length) {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    private suspend fun allSettings(): JsonObject =
        PanelJson.objectOrNull(json, request { service.allSettings() }) ?: throw AppException(AppError.PanelError("empty settings"))

    private suspend fun request(call: suspend () -> Response<ResponseBody>): JsonElement? {
        if (!loggedIn) sessionLock.withLock { if (!loggedIn) login() }
        var response = call()
        if (response.code() in SESSION_EXPIRED) {
            response.body()?.close()
            sessionLock.withLock {
                loggedIn = false
                login()
            }
            response = call()
        }
        if (!response.isSuccessful) {
            response.body()?.close()
            throw HttpException(response)
        }
        return PanelJson.unwrap(json, response.body()?.use { it.string() })
    }

    private suspend fun login() {
        cookies.clear()
        val response = service.login(username, password)
        val text = response.body()?.use { it.string() }
        if (!response.isSuccessful) throw HttpException(response)
        val success = runCatching { (json.parseToJsonElement(text.orEmpty()) as? JsonObject)?.bool("success") }.getOrNull()
        if (success != true) throw AppException(AppError.PanelUnauthorized)
        loggedIn = true
    }

    private companion object {
        val SESSION_EXPIRED = setOf(301, 302, 303, 307, 401, 404)

        /** Read-only or computed columns that 2.x's update form does not take. */
        val SKIPPED_ON_UPDATE = setOf("id", "clientStats", "userId")
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
