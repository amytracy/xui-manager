package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.app.AppearanceStore
import dev.autorasqr.xuimanager.data.db.DbTransactions
import dev.autorasqr.xuimanager.data.db.PanelEmbedded
import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.ServerEntity
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.data.transfer.TransferBundle
import dev.autorasqr.xuimanager.data.transfer.TransferCodec
import dev.autorasqr.xuimanager.data.transfer.TransferException
import dev.autorasqr.xuimanager.data.transfer.TransferPanel
import dev.autorasqr.xuimanager.data.transfer.TransferServer
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.UiStyle
import dev.autorasqr.xuimanager.domain.repository.TransferExport
import dev.autorasqr.xuimanager.domain.repository.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Look of the app, abstracted so the import can be tested without Android. */
interface UiStyleSource {
    fun current(): String?
    fun apply(name: String)
}

class AppearanceUiStyleSource @Inject constructor(private val store: AppearanceStore) : UiStyleSource {
    override fun current(): String = store.style.value.name
    override fun apply(name: String) = store.setStyle(UiStyle.parse(name))
}

class TransferRepositoryImpl @Inject constructor(
    private val dao: ServerDao,
    private val secrets: SecretStore,
    private val transactions: DbTransactions,
    private val uiStyle: UiStyleSource,
) : TransferRepository {

    override suspend fun export(): TransferExport = withContext(Dispatchers.Default) {
        val servers = withContext(Dispatchers.IO) { dao.all() }.map { it.toTransfer() }
        if (servers.isEmpty()) throw TransferException(TransferException.Reason.NOT_A_TRANSFER)
        val bundle = TransferBundle(createdAt = System.currentTimeMillis(), servers = servers, uiStyle = uiStyle.current())
        val sealed = TransferCodec.seal(bundle)
        val code = TransferCodec.newCode()
        TransferExport(
            qrCodes = TransferCodec.qrCodes(sealed),
            link = TransferCodec.link(sealed, code),
            code = code,
            serverCount = servers.size,
        )
    }

    override suspend fun openLink(link: String, code: String): TransferBundle =
        withContext(Dispatchers.Default) { TransferCodec.openLink(link, code) }

    override suspend fun import(bundle: TransferBundle): Long = withContext(Dispatchers.IO) {
        val ordered = mastersFirst(bundle.servers)
        if (ordered.isEmpty()) throw TransferException(TransferException.Reason.DAMAGED)
        val now = System.currentTimeMillis()
        val ids = HashMap<Long, Long>()
        transactions.run {
            val existing = dao.all()
            for (server in ordered) {
                val match = existing.firstOrNull { it.host.equals(server.host, ignoreCase = true) && it.sshPort == server.sshPort }
                val masterId = server.masterRef?.let { ids[it] }
                val entity = ServerEntity(
                    id = match?.id ?: 0,
                    name = server.name,
                    host = server.host,
                    sshPort = server.sshPort,
                    sshUser = server.sshUser,
                    authType = authType(server.authType),
                    hostKeyAlgorithm = server.hostKeyAlgorithm?.takeIf { server.hostKeyFingerprint != null },
                    hostKeyFingerprint = server.hostKeyFingerprint?.takeIf { server.hostKeyAlgorithm != null },
                    createdAt = match?.createdAt ?: now,
                    updatedAt = now,
                    panel = server.panel?.toEmbedded(),
                    domain = server.domain,
                    masterServerId = masterId,
                    nodeId = server.nodeId.takeIf { masterId != null },
                )
                val id = if (match != null) {
                    dao.update(entity)
                    match.id
                } else {
                    dao.insert(entity)
                }
                ids[server.ref] = id
                // Inside the transaction: if the Keystore fails, no server is left without its credentials.
                storeSecrets(id, server)
            }
        }
        bundle.uiStyle?.let(uiStyle::apply)
        val home = ordered.firstOrNull { it.masterRef == null } ?: ordered.first()
        ids.getValue(home.ref)
    }

    private fun ServerEntity.toTransfer() = TransferServer(
        ref = id,
        name = name,
        host = host,
        sshPort = sshPort,
        sshUser = sshUser,
        authType = authType.name,
        hostKeyAlgorithm = hostKeyAlgorithm,
        hostKeyFingerprint = hostKeyFingerprint,
        domain = domain,
        masterRef = masterServerId,
        nodeId = nodeId,
        panel = panel?.let {
            TransferPanel(
                version = it.version,
                port = it.port,
                webBasePath = it.webBasePath,
                username = it.username,
                subScheme = it.subScheme,
                subPort = it.subPort,
                subPath = it.subPath,
                defaultInboundId = it.defaultInboundId,
                deployedAt = it.deployedAt,
                host = it.host,
                selfstealPort = it.selfstealPort,
            )
        },
        sshPassword = secrets.get(SecretKeys.sshPassword(id)),
        sshPrivateKey = secrets.get(SecretKeys.sshPrivateKey(id)),
        sshPassphrase = secrets.get(SecretKeys.sshPassphrase(id)),
        panelPassword = secrets.get(SecretKeys.panelPassword(id)),
        panelToken = secrets.get(SecretKeys.panelToken(id)),
    )

    private fun TransferPanel.toEmbedded() = PanelEmbedded(
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

    private fun storeSecrets(id: Long, server: TransferServer) {
        secrets.removeByPrefix(SecretKeys.serverPrefix(id))
        server.sshPassword?.let { secrets.put(SecretKeys.sshPassword(id), it) }
        server.sshPrivateKey?.let { secrets.put(SecretKeys.sshPrivateKey(id), it) }
        server.sshPassphrase?.let { secrets.put(SecretKeys.sshPassphrase(id), it) }
        server.panelPassword?.let { secrets.put(SecretKeys.panelPassword(id), it) }
        server.panelToken?.let { secrets.put(SecretKeys.panelToken(id), it) }
    }

    private fun authType(name: String): SshAuthType =
        SshAuthType.entries.firstOrNull { it.name == name } ?: throw TransferException(TransferException.Reason.DAMAGED)

    companion object {
        /** Masters before their nodes, so a node's master already has its new id; a node of an unknown master stays. */
        fun mastersFirst(servers: List<TransferServer>): List<TransferServer> {
            val refs = servers.map { it.ref }.toSet()
            val placed = LinkedHashMap<Long, TransferServer>()
            var pending = servers.distinctBy { it.ref }
            while (pending.isNotEmpty()) {
                val (ready, waiting) = pending.partition { s ->
                    s.masterRef == null || s.masterRef !in refs || s.masterRef in placed || s.masterRef == s.ref
                }
                if (ready.isEmpty()) {
                    // A cycle can only come from a damaged bundle.
                    throw TransferException(TransferException.Reason.DAMAGED)
                }
                ready.forEach { placed[it.ref] = it }
                pending = waiting
            }
            return placed.values.toList()
        }
    }
}
