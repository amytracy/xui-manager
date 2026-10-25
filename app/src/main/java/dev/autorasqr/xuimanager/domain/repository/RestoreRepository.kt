package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.RestoreProgress
import kotlinx.coroutines.flow.StateFlow

interface RestoreRepository {
    /**
     * Finds the panel already installed on the server over SSH and connects the app to it as it is: the panel gets a
     * new login, password and API token, nothing is reinstalled. A node server is registered on its master again,
     * since the master reaches it with that token. Runs in the background.
     */
    fun start(serverId: Long)
    fun progress(serverId: Long): StateFlow<RestoreProgress?>
    fun clearProgress(serverId: Long)
}
