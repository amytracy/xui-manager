package dev.autorasqr.xuimanager.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 7 drops the Simple CDN mode that versions 5 and 6 added. CDN-only servers (the "More" tab list) are deleted
 * together with their jobs; a server that had CDN set up on itself stays as a plain server without a panel.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM `deploy_jobs` WHERE `serverId` IN (SELECT `id` FROM `servers` WHERE `standaloneCdn` = 1)")
        db.execSQL("DELETE FROM `servers` WHERE `standaloneCdn` = 1")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `_new_servers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`host` TEXT NOT NULL, `sshPort` INTEGER NOT NULL, `sshUser` TEXT NOT NULL, `authType` TEXT NOT NULL, " +
                "`hostKeyAlgorithm` TEXT, `hostKeyFingerprint` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`domain` TEXT, `masterServerId` INTEGER, `nodeId` INTEGER, `panel_version` TEXT, `panel_port` INTEGER, " +
                "`panel_webBasePath` TEXT, `panel_username` TEXT, `panel_subScheme` TEXT, `panel_subPort` INTEGER, " +
                "`panel_subPath` TEXT, `panel_defaultInboundId` INTEGER, `panel_deployedAt` INTEGER, `panel_host` TEXT, " +
                "`panel_selfstealPort` INTEGER)",
        )
        val columns = "`id`,`name`,`host`,`sshPort`,`sshUser`,`authType`,`hostKeyAlgorithm`,`hostKeyFingerprint`,`createdAt`," +
            "`updatedAt`,`domain`,`masterServerId`,`nodeId`,`panel_version`,`panel_port`,`panel_webBasePath`,`panel_username`," +
            "`panel_subScheme`,`panel_subPort`,`panel_subPath`,`panel_defaultInboundId`,`panel_deployedAt`,`panel_host`," +
            "`panel_selfstealPort`"
        db.execSQL("INSERT INTO `_new_servers` ($columns) SELECT $columns FROM `servers`")
        db.execSQL("DROP TABLE `servers`")
        db.execSQL("ALTER TABLE `_new_servers` RENAME TO `servers`")
    }
}
