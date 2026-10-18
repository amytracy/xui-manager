package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.domain.model.NodeDraft
import dev.autorasqr.xuimanager.domain.model.NodeInbound
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.NodesSnapshot

/** Nodes of the panel on server [serverId] (3x-ui v3). */
interface NodeRepository {
    suspend fun supported(serverId: Long): Boolean
    suspend fun load(serverId: Long): NodesSnapshot
    suspend fun get(serverId: Long, nodeId: Int): Node?

    /** Adds ([NodeDraft.id] = 0) or updates a node; the panel refuses one it can't reach. Returns the node's id. */
    suspend fun save(serverId: Long, draft: NodeDraft): Int
    suspend fun test(serverId: Long, draft: NodeDraft): NodeTestResult
    suspend fun fetchPin(serverId: Long, draft: NodeDraft): String
    suspend fun remoteInbounds(serverId: Long, draft: NodeDraft): List<NodeInbound>

    /** Outbound tags of the panel's Xray template, for reaching a node through an outbound. */
    suspend fun outboundTags(serverId: Long): List<String>

    suspend fun setEnabled(serverId: Long, nodeId: Int, enabled: Boolean)
    suspend fun probe(serverId: Long, nodeId: Int): NodeTestResult

    /** Removes the node from the panel; a server the app deployed as that node stays, unregistered. */
    suspend fun delete(serverId: Long, nodeId: Int)

    /** Registers server [nodeServerId], deployed by the app, as a node of [masterId]'s panel. Returns the node's id. */
    suspend fun register(masterId: Long, nodeServerId: Long): Int

    /** Enabled nodes a new inbound can be placed on; empty when the panel has no nodes or can't have them. */
    suspend fun choices(serverId: Long): List<NodeChoice>

    /** Panel certificate paths on the node, for TLS inbounds hosted there. */
    suspend fun webCert(serverId: Long, nodeId: Int): Pair<String, String>?
}
