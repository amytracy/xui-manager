package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.PanelUpdateOffer
import dev.autorasqr.xuimanager.domain.model.PanelUpdateProgress
import kotlinx.coroutines.flow.StateFlow

interface PanelUpdateRepository {
    /**
     * Asks the main panel whether a newer 3x-ui release exists and which of its nodes run an older one. Null when
     * there is nothing to update or the check is impossible (v2 panel, no access to GitHub). Remembered until the
     * app restarts or an update finishes; [refresh] forces a new check.
     */
    suspend fun check(serverId: Long, refresh: Boolean = false): PanelUpdateOffer?

    /** True once per app run: whether the offer dialog should still be shown for this server. */
    fun shouldPrompt(serverId: Long): Boolean
    fun markPrompted(serverId: Long)

    /** Updates the nodes in [offer] through the main panel, then the main panel itself. Runs in the background. */
    fun start(serverId: Long, offer: PanelUpdateOffer)
    fun progress(serverId: Long): StateFlow<PanelUpdateProgress?>
    fun clearProgress(serverId: Long)
}
