package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.deploy.SniPresets
import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.inbound.hostName
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelJson.string
import dev.autorasqr.xuimanager.data.panel.VlessEncAuth
import dev.autorasqr.xuimanager.domain.repository.InboundEditorRepository
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

    override suspend fun save(serverId: Long, inboundId: Int?, payload: JsonObject, fallbacks: List<JsonObject>?): Int = ioMapped {
        val api = api(serverId)
        val id = if (inboundId == null) api.addInboundRaw(payload) else inboundId.also { api.updateInbound(it, payload) }
        if (fallbacks != null) api.setFallbacks(id, fallbacks)
        api.restartXray()
        id
    }

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
}
