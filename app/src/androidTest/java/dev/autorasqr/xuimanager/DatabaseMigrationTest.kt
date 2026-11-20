package dev.autorasqr.xuimanager

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.autorasqr.xuimanager.data.db.AppDatabase
import dev.autorasqr.xuimanager.data.db.MIGRATION_6_7
import dev.autorasqr.xuimanager.data.db.toDomain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrading from 0.4.x must keep deployed panels reachable through the SSH host they were deployed with. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version2ServerKeepsItsPanelAfterUpgrade() = runTest {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, sshPort, sshUser, authType, createdAt, updatedAt, panel_version, " +
                    "panel_port, panel_webBasePath, panel_username, panel_subScheme, panel_subPort, panel_subPath, " +
                    "panel_defaultInboundId, panel_deployedAt) VALUES (1, 'vps', '203.0.113.7', 22, 'root', 'PASSWORD', 1, 1, " +
                    "'v3.10.2', 34567, '/abc/', 'admin', 'https', 45678, '/sub/', 3, 99)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 3, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_6_7).build()
        try {
            val server = room.serverDao().get(1)!!.toDomain()
            assertNull(server.domain)
            assertNull(server.panel!!.host)
            assertNull(server.panel!!.selfstealPort)
            assertEquals("203.0.113.7", server.panelHost)
        } finally {
            room.close()
        }
    }

    @Test
    fun version3ServerIsNotANodeAfterUpgrade() = runTest {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, sshPort, sshUser, authType, createdAt, updatedAt, domain) " +
                    "VALUES (1, 'vps', '203.0.113.7', 22, 'root', 'PASSWORD', 1, 1, 'vpn.example.com')",
            )
        }
        helper.runMigrationsAndValidate(dbName, 4, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_6_7).build()
        try {
            val server = room.serverDao().get(1)!!.toDomain()
            assertNull(server.masterServerId)
            assertNull(server.nodeId)
            assertEquals("vpn.example.com", server.domain)
        } finally {
            room.close()
        }
    }

    @Test
    fun version6DropsCdnOnlyServersAndKeepsTheRest() = runTest {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, sshPort, sshUser, authType, createdAt, updatedAt, standaloneCdn, " +
                    "panel_version, panel_port, panel_webBasePath, panel_username, panel_subScheme, panel_subPort, " +
                    "panel_subPath, panel_deployedAt) VALUES (1, 'vps', '203.0.113.7', 22, 'root', 'PASSWORD', 1, 1, 0, " +
                    "'v3.10.2', 34567, '/abc/', 'admin', 'https', 45678, '/sub/', 99)",
            )
            // A server that had CDN on itself (1.1.0) and a CDN-only server from the "More" tab (1.2.0).
            db.execSQL(
                "INSERT INTO servers (id, name, host, sshPort, sshUser, authType, createdAt, updatedAt, standaloneCdn, " +
                    "cdn_port, cdn_path, cdn_mode, cdn_preset, cdn_fingerprint, cdn_remark, cdn_xrayVersion, cdn_deployedAt) " +
                    "VALUES (2, 'own', '203.0.113.8', 22, 'root', 'PASSWORD', 1, 1, 0, 10000, '/x', 'auto', 1, 'chrome', 'r', 'v1', 1)",
            )
            db.execSQL(
                "INSERT INTO servers (id, name, host, sshPort, sshUser, authType, createdAt, updatedAt, standaloneCdn, " +
                    "cdn_port, cdn_path, cdn_mode, cdn_preset, cdn_fingerprint, cdn_remark, cdn_xrayVersion, cdn_deployedAt) " +
                    "VALUES (3, 'cdn', '203.0.113.9', 22, 'root', 'PASSWORD', 1, 1, 1, 10000, '/x', 'auto', 1, 'chrome', 'r', 'v1', 1)",
            )
            db.execSQL(
                "INSERT INTO deploy_jobs (id, serverId, mode, paramsJson, remoteDir, state, warnings, startedAt) " +
                    "VALUES (1, 3, 'ONE_CLICK', '{}', '/root', 'SUCCEEDED', '', 1)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_6_7).build()
        try {
            val server = room.serverDao().get(1)!!.toDomain()
            assertEquals(34567, server.panel!!.port)
            val own = room.serverDao().get(2)!!.toDomain()
            assertNull(own.panel)
            assertNull(room.serverDao().get(3))
            assertNull(room.deployJobDao().get(1))
        } finally {
            room.close()
        }
    }

    @Test
    fun version4UpgradesThroughTheCdnVersions() = runTest {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, sshPort, sshUser, authType, createdAt, updatedAt, masterServerId, nodeId) " +
                    "VALUES (1, 'node', '203.0.113.7', 22, 'root', 'PASSWORD', 1, 1, 5, 2)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_6_7).build()
        try {
            val server = room.serverDao().get(1)!!.toDomain()
            assertEquals(5L, server.masterServerId)
            assertEquals(2, server.nodeId)
        } finally {
            room.close()
        }
    }
}
