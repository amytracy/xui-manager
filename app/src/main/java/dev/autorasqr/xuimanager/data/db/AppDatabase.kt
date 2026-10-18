package dev.autorasqr.xuimanager.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.SshAuthType

@Database(
    entities = [ServerEntity::class, DeployJobEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.DropCertificatePin::class),
        // Version 3: optional server domain, the panel's certificate host and the self-steal port.
        AutoMigration(from = 2, to = 3),
        // Version 4: servers deployed as nodes of another server's panel.
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun deployJobDao(): DeployJobDao

    /** Version 2 trusts the panel's Let's Encrypt certificate through the system store; the pin column is gone. */
    @DeleteColumn(tableName = "servers", columnName = "panel_certSha256")
    class DropCertificatePin : AutoMigrationSpec
}

class Converters {
    @TypeConverter fun authTypeToString(value: SshAuthType): String = value.name
    @TypeConverter fun stringToAuthType(value: String): SshAuthType = SshAuthType.valueOf(value)
    @TypeConverter fun modeToString(value: DeployMode): String = value.name
    @TypeConverter fun stringToMode(value: String): DeployMode = DeployMode.valueOf(value)
    @TypeConverter fun stateToString(value: DeployState): String = value.name
    @TypeConverter fun stringToState(value: String): DeployState = DeployState.valueOf(value)
}
