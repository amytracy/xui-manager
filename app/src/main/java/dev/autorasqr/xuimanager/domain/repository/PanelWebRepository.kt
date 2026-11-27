package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.PanelWebSession

interface PanelWebRepository {
    /** Signs in to the panel over pinned HTTPS and returns the session for the WebView. */
    suspend fun openSession(serverId: Long): PanelWebSession

    /**
     * Opens node [nodeId] of [masterId]'s panel at the address the main panel has for it. The app has no password for a
     * node it can't reach over SSH, so the panel's own login page is shown.
     */
    suspend fun openNodeSession(masterId: Long, nodeId: Int): PanelWebSession
}
