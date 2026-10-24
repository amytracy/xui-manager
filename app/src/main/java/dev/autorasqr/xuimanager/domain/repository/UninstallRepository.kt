package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.UninstallProgress
import kotlinx.coroutines.flow.StateFlow

interface UninstallRepository {
    /**
     * Removes 3x-ui and everything else the app installed from the server over SSH, first from the servers deployed
     * as its nodes, and deletes each cleaned server from the app. Stops at the first failure. Runs in the background.
     */
    fun start(serverId: Long)
    fun progress(serverId: Long): StateFlow<UninstallProgress?>
    fun clearProgress(serverId: Long)
}
