package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import dev.autorasqr.xuimanager.domain.model.XrayUpdateProgress
import kotlinx.coroutines.flow.StateFlow

interface XrayUpdateRepository {
    /**
     * Compares the server's running Xray-core with the newest release the panel can install. Works for any server
     * with a panel, nodes included. Remembered until the app restarts or an update finishes; [refresh] checks again.
     */
    suspend fun check(serverId: Long, refresh: Boolean = false): UpdateCheck<XrayUpdateOffer>

    /** True once per app run: whether the offer dialog should still be shown for this server. */
    fun shouldPrompt(serverId: Long): Boolean
    fun markPrompted(serverId: Long)

    /** Installs [XrayUpdateOffer.latestVersion] through the panel and waits for Xray to run it. Runs in the background. */
    fun start(serverId: Long, offer: XrayUpdateOffer)
    fun progress(serverId: Long): StateFlow<XrayUpdateProgress?>
    fun clearProgress(serverId: Long)
}
