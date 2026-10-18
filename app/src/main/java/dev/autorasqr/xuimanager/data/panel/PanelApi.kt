package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Version-independent view of the 3x-ui HTTP API. Each supported panel generation gets its own adapter;
 * [PanelApiFactory] picks one from the panel version recorded at deploy time.
 */
interface PanelApi {
    val capabilities: PanelCapabilities

    suspend fun status(): PanelStatus

    suspend fun listClients(): List<PanelClient>
    suspend fun onlineEmails(): Set<String>
    suspend fun addClient(client: NewClient)

    /** [email] is the client's current email; [changes] may rename it. */
    suspend fun updateClient(email: String, changes: ClientChanges)
    suspend fun deleteClient(email: String)
    suspend fun setClientEnabled(email: String, enabled: Boolean)
    suspend fun resetClientTraffic(email: String)

    suspend fun listInbounds(): List<PanelInbound>
    suspend fun addInbound(spec: InboundSpec): Int

    /** The inbound as stored, with settings, streamSettings and sniffing as nested objects. */
    suspend fun getInbound(id: Int): JsonObject

    /**
     * Saves [inbound] (in the shape [getInbound] returns). Clients are kept as stored: v3 ignores them here and v2
     * gets back the settings it returned, so edit only what you mean to change.
     */
    suspend fun updateInbound(id: Int, inbound: JsonObject)
    suspend fun deleteInbound(id: Int)
    suspend fun setInboundEnabled(id: Int, enabled: Boolean)

    suspend fun subscriptionSettings(): SubscriptionSettings
    suspend fun updateSubscriptionSettings(update: SubscriptionUpdate)

    /** All panel settings (the AllSetting object). */
    suspend fun settings(): JsonObject

    /** Writes [changes] over the current settings; everything else stays as it is. */
    suspend fun patchSettings(changes: Map<String, JsonElement>)

    /** Only when [PanelCapabilities.routing]. */
    suspend fun xrayTemplate(): XrayTemplate

    /** Saves the template; the panel validates it and applies it to the running core. */
    suspend fun saveXrayTemplate(template: XrayTemplate)

    suspend fun restartPanel()
    suspend fun restartXray()

    suspend fun newX25519(): X25519Keys

    /** Empty when the panel can't probe targets (see [PanelCapabilities.realityScan]). */
    suspend fun scanRealityTargets(candidates: List<String>): List<RealityTargetVerdict>

    // --- the full inbound editor (3x-ui v3, see [PanelCapabilities.routing]) ---

    /** Creates an inbound from the editor's payload (nested settings objects) and returns its id. */
    suspend fun addInboundRaw(inbound: JsonObject): Int = unsupported()

    /** ML-DSA-65 seed (server) and verify key (clients) for Reality. */
    suspend fun newMldsa65(): Pair<String, String> = unsupported()

    /** VLESS encryption candidates, one per auth kind. */
    suspend fun newVlessEncAuths(): List<VlessEncAuth> = unsupported()

    /** Probes one Reality target from the server; the panel's RealityScanResult as is. */
    suspend fun scanRealityTarget(target: String, sni: String, xver: Int, allowPrivate: Boolean): JsonObject = unsupported()

    /** ECH server keys and the matching client config list for [sni]. */
    suspend fun newEchCert(sni: String): Pair<String, String> = unsupported()

    suspend fun certHash(certFile: String, certContent: String): List<String> = unsupported()
    suspend fun remoteCertHash(server: String, allowPrivate: Boolean): List<String> = unsupported()

    /** Fallback children of a VLESS/Trojan TCP master (v3 stores them as links to other inbounds). */
    suspend fun fallbacks(masterId: Int): List<JsonObject> = unsupported()
    suspend fun setFallbacks(masterId: Int, fallbacks: List<JsonObject>): Unit = unsupported()

    // --- nodes: other 3x-ui panels managed by this one (3x-ui v3, see [PanelCapabilities.nodes]) ---

    suspend fun listNodes(): List<PanelNode> = unsupported()

    /** The panel probes the node first and refuses one it can't reach. Returns the new node's id. */
    suspend fun addNode(node: NodeMutation): Int = unsupported()
    suspend fun updateNode(id: Int, node: NodeMutation): Unit = unsupported()

    /** Inbounds hosted on the node are not migrated. */
    suspend fun deleteNode(id: Int): Unit = unsupported()
    suspend fun setNodeEnabled(id: Int, enabled: Boolean): Unit = unsupported()

    /** Probes a saved node and updates its cached health on the panel. */
    suspend fun probeNode(id: Int): NodeProbe = unsupported()

    /** Probes unsaved connection details. */
    suspend fun testNode(node: NodeMutation): NodeProbe = unsupported()

    /** SHA-256 (base64) of the node's leaf certificate, fetched without verifying it, for tlsVerifyMode = pin. */
    suspend fun nodeCertFingerprint(node: NodeMutation): String = unsupported()
    suspend fun nodeRemoteInbounds(node: NodeMutation): List<RemoteInboundOption> = unsupported()

    /** The node's own panel certificate and key paths, for TLS inbounds hosted on it. */
    suspend fun nodeWebCert(id: Int): Pair<String, String>? = unsupported()
}

data class VlessEncAuth(val id: String, val label: String, val decryption: String, val encryption: String)

private fun unsupported(): Nothing = throw AppException(AppError.PanelError("needs 3x-ui v3"))
