package dev.autorasqr.xuimanager.domain.repository

import kotlinx.serialization.json.JsonObject

/** Panel side of the full inbound editor (3x-ui v3). Inbounds travel as the panel's JSON. */
interface InboundEditorRepository {
    /** False on 3x-ui 2.x, which the editor does not support. */
    suspend fun supported(serverId: Long): Boolean

    /** The inbound as stored plus its fallback rows (empty unless it is a VLESS/Trojan TCP master). */
    suspend fun load(serverId: Long, inboundId: Int): Pair<JsonObject, List<JsonObject>>

    /** id, remark, protocol and port of every inbound, for choosing fallback children. */
    suspend fun otherInbounds(serverId: Long): List<JsonObject>

    /**
     * Adds ([inboundId] null) or updates the inbound with [payload], saves [fallbacks] when given, restarts Xray and
     * returns the inbound id.
     */
    suspend fun save(serverId: Long, inboundId: Int?, payload: JsonObject, fallbacks: List<JsonObject>?): Int

    suspend fun newX25519(serverId: Long): Pair<String, String>
    suspend fun newMldsa65(serverId: Long): Pair<String, String>

    /** decryption to encryption for the auth kind ("x25519", "mlkem768_xorpub", …), or null if the panel has none. */
    suspend fun newVlessEnc(serverId: Long, authKind: String): Pair<String, String>?
    suspend fun scanTarget(serverId: Long, target: String, sni: String, xver: Int): JsonObject

    /** The first usable candidate among the presets, as host to latency. */
    suspend fun findTarget(serverId: Long): Pair<String, Long>?
    suspend fun newEchCert(serverId: Long, sni: String): Pair<String, String>

    /**
     * The panel's own certificate and key files (the domain's Let's Encrypt certificate after a deploy); with [nodeId],
     * the files of that node's panel.
     */
    suspend fun panelCertFiles(serverId: Long, nodeId: Int? = null): Pair<String, String>?
    suspend fun certHash(serverId: Long, certFile: String, certContent: String): List<String>
    suspend fun remoteCertHash(serverId: Long, server: String): List<String>
}
