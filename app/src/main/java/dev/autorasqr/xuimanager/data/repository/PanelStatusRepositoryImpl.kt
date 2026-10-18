package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.error.ioMapped
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.domain.model.ServerStatus
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PanelStatusRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
) : PanelStatusRepository {

    override suspend fun status(serverId: Long): ServerStatus = ioMapped {
        val (server, api) = panels.forServer(serverId)
        val status = api.status()
        ServerStatus(
            cpuPercent = status.cpuPercent,
            memUsed = status.memUsed,
            memTotal = status.memTotal,
            loads = status.loads,
            uptimeSeconds = status.uptimeSeconds,
            xrayState = status.xrayState,
            xrayVersion = status.xrayVersion,
            xrayError = status.xrayError.takeIf { it.isNotBlank() },
            panelVersion = status.panelVersion.ifBlank { server.panel?.version.orEmpty() },
        )
    }

    override suspend fun restartXray(serverId: Long) = ioMapped {
        panels.forServer(serverId).second.restartXray()
    }
}
