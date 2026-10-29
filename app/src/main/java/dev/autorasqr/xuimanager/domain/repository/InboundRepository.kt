package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.InboundsSnapshot
import dev.autorasqr.xuimanager.domain.model.SelfStealProgress
import dev.autorasqr.xuimanager.domain.model.SniCheck
import kotlinx.coroutines.flow.StateFlow

interface InboundRepository {
    suspend fun load(serverId: Long): InboundsSnapshot
    suspend fun setEnabled(serverId: Long, inboundId: Int, enabled: Boolean)
    suspend fun delete(serverId: Long, inboundId: Int)

    /** Points a Reality inbound at another site and/or fingerprint; keys, short ids and clients stay. */
    suspend fun updateReality(serverId: Long, inboundId: Int, sni: String, fingerprint: String)

    /** Null when the panel cannot probe targets. With [nodeId], probes from that node when the app manages it. */
    suspend fun checkSni(serverId: Long, host: String, nodeId: Int? = null): SniCheck?

    fun selfStealProgress(serverId: Long): StateFlow<SelfStealProgress?>

    /**
     * Sets up the decoy site over SSH and then either moves inbound [convertInboundId] onto it or creates a new
     * self-steal inbound. [sitePage] is the user's own index.html; null picks a random template.
     * Runs in the background; follow it with [selfStealProgress].
     */
    fun installSelfSteal(serverId: Long, convertInboundId: Int?, fingerprint: String, sitePage: ByteArray?)

    /**
     * Replaces the page of an installed decoy site; null puts a fresh random template there. The site is on
     * [siteServerId] (a node's own server) while the progress is reported for [serverId].
     */
    fun updateSitePage(serverId: Long, sitePage: ByteArray?, siteServerId: Long = serverId)

    /** Stops and deletes the decoy site. Inbounds that still point at it must be changed or deleted first. */
    fun removeSelfSteal(serverId: Long)

    fun clearSelfStealProgress(serverId: Long)
}
