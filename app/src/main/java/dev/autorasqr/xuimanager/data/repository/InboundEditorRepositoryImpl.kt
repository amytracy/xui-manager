package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.deploy.SniPresets
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.inbound.hostName
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelJson.int
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import dev.autorasqr.xuimanager.data.panel.VlessEncAuth
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.InboundSaveStep
import dev.autorasqr.xuimanager.domain.repository.InboundEditorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboundEditorRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
) : InboundEditorRepository {

    private suspend fun api(serverId: Long) = panels.forServer(serverId).second

    override suspend fun supported(serverId: Long): Boolean = ioMapped { api(serverId).capabilities.routing }

    override suspend fun load(serverId: Long, inboundId: Int): Pair<JsonObject, List<JsonObject>> = ioMapped {
        val api = api(serverId)
        val inbound = api.getInbound(inboundId)
        val protocol = inbound.string("protocol")
        val fallbacks = if (protocol == "vless" || protocol == "trojan") runCatching { api.fallbacks(inboundId) }.getOrDefault(emptyList()) else emptyList()
        inbound to fallbacks
    }

    override suspend fun otherInbounds(serverId: Long): List<JsonObject> = ioMapped {
        api(serverId).listInbounds().map { ib ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(ib.id), "remark" to JsonPrimitive(ib.remark),
                    "protocol" to JsonPrimitive(ib.protocol), "port" to JsonPrimitive(ib.port),
                ),
            )
        }
    }

    override suspend fun save(serverId: Long, inboundId: Int?, payload: JsonObject, fallbacks: List<JsonObject>?): Int =
        ioMapped { api(serverId).saveInbound(inboundId, payload, fallbacks) }

    override suspend fun newX25519(serverId: Long): Pair<String, String> = ioMapped {
        val keys = api(serverId).newX25519()
        keys.privateKey to keys.publicKey
    }

    override suspend fun newMldsa65(serverId: Long): Pair<String, String> = ioMapped { api(serverId).newMldsa65() }

    override suspend fun newVlessEnc(serverId: Long, authKind: String): Pair<String, String>? = ioMapped {
        api(serverId).newVlessEncAuths().firstOrNull { matches(it, authKind) }?.let { it.decryption to it.encryption }
    }

    /** InboundFormModal.matchesVlessAuth: by id, or by the label for panels that send none. */
    private fun matches(auth: VlessEncAuth, kind: String): Boolean {
        if (auth.id == kind) return true
        val label = auth.label.lowercase().replace(Regex("[-_\\s]"), "")
        return when (kind) {
            "mlkem768" -> "mlkem768" in label && "xorpub" !in label && "random" !in label
            "x25519" -> "x25519" in label && "xorpub" !in label && "random" !in label
            "mlkem768_xorpub" -> "mlkem768" in label && "xorpub" in label
            "mlkem768_random" -> "mlkem768" in label && "random" in label
            "x25519_xorpub" -> "x25519" in label && "xorpub" in label
            "x25519_random" -> "x25519" in label && "random" in label
            else -> false
        }
    }

    override suspend fun scanTarget(serverId: Long, target: String, sni: String, xver: Int): JsonObject =
        ioMapped { api(serverId).scanRealityTarget(target, sni, xver, allowPrivate = false) }

    override suspend fun findTarget(serverId: Long): Pair<String, Long>? = ioMapped {
        api(serverId).scanRealityTargets(SniPresets.targets).firstOrNull { it.feasible }?.let { v -> v.hostName()?.let { it to v.latencyMs } }
    }

    override suspend fun newEchCert(serverId: Long, sni: String): Pair<String, String> = ioMapped { api(serverId).newEchCert(sni) }

    override suspend fun panelCertFiles(serverId: Long, nodeId: Int?): Pair<String, String>? = ioMapped {
        if (nodeId != null) return@ioMapped api(serverId).nodeWebCert(nodeId)
        val settings = api(serverId).settings()
        val cert = settings.string("webCertFile").orEmpty()
        val key = settings.string("webKeyFile").orEmpty()
        if (cert.isEmpty() && key.isEmpty()) null else cert to key
    }

    override suspend fun certHash(serverId: Long, certFile: String, certContent: String): List<String> =
        ioMapped { api(serverId).certHash(certFile, certContent) }

    override suspend fun remoteCertHash(serverId: Long, server: String): List<String> =
        ioMapped { api(serverId).remoteCertHash(server, allowPrivate = false) }

    companion object {
        private val PLAIN = listOf("remark", "port", "listen", "protocol")

        /**
         * True when the stored inbound changed and its plain fields now match [payload]: the update landed although
         * the panel answered with an error. Nested settings are only compared with [before], since the panel
         * normalizes them on save.
         */
        internal fun landed(before: JsonObject, after: JsonObject?, payload: JsonObject): Boolean {
            if (after == null) return false
            fun JsonObject.view() = (PLAIN + listOf("streamSettings", "sniffing")).associateWith { this[it] }
            return after.view() != before.view() && PLAIN.filter { it in payload }.all { after[it] == payload[it] }
        }
    }
}

/**
 * Adds or updates the inbound, then its fallbacks and the Xray restart. Once the inbound itself is stored, a later
 * failure (or an error the panel returns for a row it did write) comes back as [AppError.InboundSavedWithWarning].
 */
internal suspend fun PanelApi.saveInbound(inboundId: Int?, payload: JsonObject, fallbacks: List<JsonObject>?): Int {
    var warning: AppError? = null
    val id = if (inboundId == null) {
        addInboundRaw(payload)
    } else {
        val before = getInbound(inboundId)
        try {
            updateInbound(inboundId, payload)
        } catch (e: AppException) {
            // The panel may answer with an error for a row it has already written; then only report it.
            if (e.error !is AppError.PanelError || !InboundEditorRepositoryImpl.landed(before, runCatching { getInbound(inboundId) }.getOrNull(), payload)) throw e
            warning = AppError.InboundSavedWithWarning(InboundSaveStep.UPDATE, e.error)
        }
        inboundId
    }
    // Once the inbound is stored, a failing follow-up step must not look like "nothing was saved".
    suspend fun step(name: InboundSaveStep, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (warning == null) warning = AppError.InboundSavedWithWarning(name, e.toAppError())
        }
    }
    if (fallbacks != null) step(InboundSaveStep.FALLBACKS) { setFallbacks(id, fallbacks) }
    // An inbound on a node runs on the node: the main panel only stores it and syncs the node itself.
    if (payload.int("nodeId")?.takeIf { it > 0 } == null) step(InboundSaveStep.XRAY_RESTART) { restartXray() }
    warning?.let { throw AppException(it) }
    return id
}
