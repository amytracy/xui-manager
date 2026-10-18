package dev.autorasqr.xuimanager

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.autorasqr.xuimanager.data.db.AppDatabase
import dev.autorasqr.xuimanager.data.db.DeployJobEntity
import dev.autorasqr.xuimanager.data.db.ServerEntity
import dev.autorasqr.xuimanager.data.db.toDomain
import dev.autorasqr.xuimanager.data.db.toEmbedded
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseDeviceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()

    @After
    fun tearDown() = db.close()

    private fun server(panel: PanelInfo? = null) = ServerEntity(
        name = "vps", host = "203.0.113.7", sshPort = 22, sshUser = "root", authType = SshAuthType.PRIVATE_KEY,
        hostKeyAlgorithm = "ssh-ed25519", hostKeyFingerprint = "SHA256:x", createdAt = 1, updatedAt = 1,
        panel = panel?.toEmbedded(),
    )

    @Test
    fun panelIsNullUntilDeployedThenRoundTrips() = runTest {
        val id = db.serverDao().insert(server())
        assertNull(db.serverDao().get(id)!!.toDomain().panel)

        val panel = PanelInfo("v3.10.2", 34567, "/abc/", "admin", "https", 45678, "/sub/", 3, 99)
        db.serverDao().update(db.serverDao().get(id)!!.copy(panel = panel.toEmbedded()))
        val loaded = db.serverDao().get(id)!!.toDomain()
        assertEquals(panel, loaded.panel)
        assertEquals("ssh-ed25519", loaded.hostKey?.algorithm)
    }

    @Test
    fun domainAndPanelHostRoundTrip() = runTest {
        val id = db.serverDao().insert(server().copy(domain = "vpn.example.com"))
        val panel = PanelInfo("v3.10.2", 34567, "/abc/", "admin", "https", 45678, "/sub/", 3, 99, "vpn.example.com", 8443)
        db.serverDao().update(db.serverDao().get(id)!!.copy(panel = panel.toEmbedded()))
        val loaded = db.serverDao().get(id)!!.toDomain()
        assertEquals("vpn.example.com", loaded.domain)
        assertEquals(panel, loaded.panel)
        assertEquals("vpn.example.com", loaded.panelHost)
    }

    @Test
    fun deletingAServerDeletesItsJobs() = runTest {
        val id = db.serverDao().insert(server())
        val job = db.deployJobDao().insert(
            DeployJobEntity(
                serverId = id, mode = DeployMode.MANUAL, paramsJson = "{}", remoteDir = "", state = DeployState.RUNNING,
                stage = null, errorCode = null, warnings = "", startedAt = 1, launchedAt = null, finishedAt = null,
            ),
        )
        assertEquals(job, db.deployJobDao().latestInState(id)?.id)
        db.serverDao().delete(id)
        assertNull(db.deployJobDao().get(job))
    }
}
