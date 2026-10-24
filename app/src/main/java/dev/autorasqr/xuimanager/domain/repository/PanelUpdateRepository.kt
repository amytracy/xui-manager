package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.PanelUpdateOffer
import dev.autorasqr.xuimanager.domain.model.PanelUpdateProgress
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import kotlinx.coroutines.flow.StateFlow

interface PanelUpdateRepository {
    /**
     * Asks the main panel whether a newer 3x-ui release exists and which of its nodes run an older one. Unavailable
     * for nodes and v2 panels (no error) or when the check fails, e.g. without access to GitHub (with the error).
     * Remembered until the app restarts or an update finishes; [refresh] forces a new check.
     */
    suspend fun check(serverId: Long, refresh: Boolean = false): UpdateCheck<PanelUpdateOffer>

    /** True once per app run: whether the offer dialog should still be shown for this server. */
    fun shouldPrompt(serverId: Long): Boolean
    fun markPrompted(serverId: Long)

    /** Updates the nodes in [offer] through the main panel, then the main panel itself. Runs in the background. */
    fun start(serverId: Long, offer: PanelUpdateOffer)
    fun progress(serverId: Long): StateFlow<PanelUpdateProgress?>
    fun clearProgress(serverId: Long)
}
