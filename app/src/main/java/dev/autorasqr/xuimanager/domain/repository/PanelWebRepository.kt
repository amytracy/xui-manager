package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.PanelWebSession

interface PanelWebRepository {
    /** Signs in to the panel over pinned HTTPS and returns the session for the WebView. */
    suspend fun openSession(serverId: Long): PanelWebSession
}
