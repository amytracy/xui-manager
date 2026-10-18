package dev.autorasqr.xuimanager.data.db

import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.Server

fun ServerEntity.toDomain() = Server(
    id = id,
    name = name,
    host = host,
    sshPort = sshPort,
    sshUser = sshUser,
    authType = authType,
    hostKey = if (hostKeyAlgorithm != null && hostKeyFingerprint != null) HostKeyPin(hostKeyAlgorithm, hostKeyFingerprint) else null,
    createdAt = createdAt,
    updatedAt = updatedAt,
    panel = panel?.toDomain(),
    domain = domain,
    masterServerId = masterServerId,
    nodeId = nodeId,
)

fun PanelEmbedded.toDomain() = PanelInfo(
    version = version,
    port = port,
    webBasePath = webBasePath,
    username = username,
    subScheme = subScheme,
    subPort = subPort,
    subPath = subPath,
    defaultInboundId = defaultInboundId,
    deployedAt = deployedAt,
    host = host,
    selfstealPort = selfstealPort,
)

fun PanelInfo.toEmbedded() = PanelEmbedded(
    version = version,
    port = port,
    webBasePath = webBasePath,
    username = username,
    subScheme = subScheme,
    subPort = subPort,
    subPath = subPath,
    defaultInboundId = defaultInboundId,
    deployedAt = deployedAt,
    host = host,
    selfstealPort = selfstealPort,
)
