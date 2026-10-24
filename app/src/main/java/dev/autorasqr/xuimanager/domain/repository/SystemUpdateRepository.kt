package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.SystemUpdateProgress
import kotlinx.coroutines.flow.StateFlow

interface SystemUpdateRepository {
    /** apt (or dnf) update and upgrade over SSH; reboots the server when the upgrade asks for it. Runs in the background. */
    fun start(serverId: Long)
    fun progress(serverId: Long): StateFlow<SystemUpdateProgress?>
    fun clearProgress(serverId: Long)
}
