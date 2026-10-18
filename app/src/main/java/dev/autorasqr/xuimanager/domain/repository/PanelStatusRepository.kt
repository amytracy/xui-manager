package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.ServerStatus

interface PanelStatusRepository {
    suspend fun status(serverId: Long): ServerStatus
    suspend fun restartXray(serverId: Long)
}
