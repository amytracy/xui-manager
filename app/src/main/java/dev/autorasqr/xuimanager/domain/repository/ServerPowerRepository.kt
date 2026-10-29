package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.RebootProgress
import kotlinx.coroutines.flow.StateFlow

interface ServerPowerRepository {
    /** Restarts the 3x-ui process and returns once the panel answers again. */
    suspend fun restartPanel(serverId: Long)

    /** Reboots the server over SSH and follows it until it is back. Runs in the background. */
    fun reboot(serverId: Long)
    fun rebootProgress(serverId: Long): StateFlow<RebootProgress?>
    fun clearRebootProgress(serverId: Long)
}
