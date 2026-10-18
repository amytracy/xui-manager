package dev.autorasqr.xuimanager.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.SshAuthType

/** No secrets here: passwords, keys and API tokens live in the Keystore-backed SecretStore. */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val sshPort: Int,
    val sshUser: String,
    val authType: SshAuthType,
    val hostKeyAlgorithm: String?,
    val hostKeyFingerprint: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @Embedded(prefix = "panel_") val panel: PanelEmbedded?,
    val domain: String? = null,
    /** Set for a server deployed as a node of another server's panel. */
    val masterServerId: Long? = null,
    /** The node's id on the master panel once it is registered there. */
    val nodeId: Int? = null,
)

data class PanelEmbedded(
    val version: String,
    val port: Int,
    val webBasePath: String,
    val username: String,
    val subScheme: String,
    val subPort: Int,
    val subPath: String,
    val defaultInboundId: Int?,
    val deployedAt: Long,
    val host: String? = null,
    val selfstealPort: Int? = null,
)

@Entity(
    tableName = "deploy_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class DeployJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val mode: DeployMode,
    /** Serialized StoredDeployParams, without secrets. */
    val paramsJson: String,
    val remoteDir: String,
    val state: DeployState,
    val stage: String?,
    val errorCode: String?,
    /** Comma-separated DeployWarning names. */
    val warnings: String,
    val startedAt: Long,
    val launchedAt: Long?,
    val finishedAt: Long?,
)
