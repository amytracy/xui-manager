package dev.autorasqr.xuimanager.data.transfer

import dev.autorasqr.xuimanager.data.db.DbTransactions
import dev.autorasqr.xuimanager.data.db.ServerDao
import dev.autorasqr.xuimanager.data.db.ServerEntity
import dev.autorasqr.xuimanager.data.repository.TransferRepositoryImpl
import dev.autorasqr.xuimanager.data.repository.UiStyleSource
import dev.autorasqr.xuimanager.data.secure.SecretKeys
import dev.autorasqr.xuimanager.data.secure.SecretStore
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferImportTest {
    private class FakeDao : ServerDao {
        val rows = linkedMapOf<Long, ServerEntity>()
        private var nextId = 100L
        override fun observeAll(): Flow<List<ServerEntity>> = flowOf(rows.values.toList())
        override fun observe(id: Long): Flow<ServerEntity?> = flowOf(rows[id])
        override suspend fun get(id: Long) = rows[id]
        override suspend fun all() = rows.values.sortedBy { it.id }
        override suspend fun insert(entity: ServerEntity): Long = nextId++.also { rows[it] = entity.copy(id = it) }
        override suspend fun update(entity: ServerEntity) { rows[entity.id] = entity }
        override suspend fun updateHostKey(id: Long, algorithm: String, fingerprint: String, now: Long) = Unit
        override suspend fun nodesOf(masterId: Long) = rows.values.filter { it.masterServerId == masterId }
        override fun observeNodesOf(masterId: Long): Flow<List<ServerEntity>> = flowOf(emptyList())
        override suspend fun updateNodeId(id: Long, nodeId: Int?, now: Long) = Unit
        override suspend fun updatePanelVersion(id: Long, version: String, now: Long) = Unit
        override suspend fun delete(id: Long) { rows.remove(id) }
    }

    private class FakeSecrets : SecretStore {
        val map = sortedMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String) = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun removeByPrefix(prefix: String) { map.keys.removeIf { it.startsWith(prefix) } }
    }

    private object Direct : DbTransactions {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    private class Style(var name: String? = "MATERIAL_YOU") : UiStyleSource {
        override fun current() = name
        override fun apply(name: String) { this.name = name }
    }

    private fun entity(id: Long, host: String, master: Long? = null) = ServerEntity(
        id = id, name = "s$id", host = host, sshPort = 22, sshUser = "root", authType = SshAuthType.PASSWORD,
        hostKeyAlgorithm = "ssh-ed25519", hostKeyFingerprint = "SHA256:$id", createdAt = 1, updatedAt = 1, panel = null,
        masterServerId = master, nodeId = master?.let { 7 },
    )

    @Test
    fun exportThenImportOnAnotherPhoneKeepsNodesAndSecrets() = runTest {
        val oldDao = FakeDao().apply {
            rows[1] = entity(1, "203.0.113.1")
            rows[2] = entity(2, "203.0.113.2", master = 1)
        }
        val oldSecrets = FakeSecrets().apply {
            put(SecretKeys.sshPassword(1), "p1")
            put(SecretKeys.panelToken(1), "t1")
            put(SecretKeys.sshPassword(2), "p2")
        }
        val export = TransferRepositoryImpl(oldDao, oldSecrets, Direct, Style("ONE_UI")).export()
        val collector = TransferCodec.QrCollector()
        export.qrCodes.forEach { collector.add(it) }

        // The new phone already knows the node's host (stale password) and has another server.
        val newDao = FakeDao().apply {
            rows[5] = entity(5, "198.51.100.9")
            rows[6] = entity(6, "203.0.113.2")
        }
        val newSecrets = FakeSecrets().apply {
            put(SecretKeys.sshPassword(6), "stale")
            put(SecretKeys.panelPassword(6), "stale-panel")
        }
        val style = Style()
        val home = TransferRepositoryImpl(newDao, newSecrets, Direct, style).import(collector.open())

        val master = newDao.rows.getValue(home)
        assertEquals("203.0.113.1", master.host)
        assertNull(master.masterServerId)
        val node = newDao.rows.getValue(6)
        assertEquals(home, node.masterServerId)
        assertEquals(7, node.nodeId)
        assertEquals("SHA256:2", node.hostKeyFingerprint)
        assertEquals("p2", newSecrets.get(SecretKeys.sshPassword(6)))
        assertNull(newSecrets.get(SecretKeys.panelPassword(6)))
        assertEquals("t1", newSecrets.get(SecretKeys.panelToken(home)))
        assertEquals(3, newDao.rows.size)
        assertEquals("ONE_UI", style.name)
    }

    @Test
    fun mastersComeBeforeTheirNodes() {
        fun s(ref: Long, master: Long?) = TransferServer(ref, "n", "h$ref", 22, "root", "PASSWORD", masterRef = master)
        val order = TransferRepositoryImpl.mastersFirst(listOf(s(3, 1), s(2, 1), s(1, null), s(4, 99)))
        assertEquals(listOf(1L, 4L, 3L, 2L), order.map { it.ref })
    }
}
