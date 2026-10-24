package dev.autorasqr.xuimanager.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.autorasqr.xuimanager.domain.model.DeployState
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observe(id: Long): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): ServerEntity?

    @Insert
    suspend fun insert(entity: ServerEntity): Long

    @Update
    suspend fun update(entity: ServerEntity)

    @Query("UPDATE servers SET hostKeyAlgorithm = :algorithm, hostKeyFingerprint = :fingerprint, updatedAt = :now WHERE id = :id")
    suspend fun updateHostKey(id: Long, algorithm: String, fingerprint: String, now: Long)

    @Query("SELECT * FROM servers WHERE masterServerId = :masterId ORDER BY id")
    suspend fun nodesOf(masterId: Long): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE masterServerId = :masterId ORDER BY id")
    fun observeNodesOf(masterId: Long): Flow<List<ServerEntity>>

    @Query("UPDATE servers SET nodeId = :nodeId, updatedAt = :now WHERE id = :id")
    suspend fun updateNodeId(id: Long, nodeId: Int?, now: Long)

    @Query("UPDATE servers SET panel_version = :version, updatedAt = :now WHERE id = :id")
    suspend fun updatePanelVersion(id: Long, version: String, now: Long)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DeployJobDao {
    @Insert
    suspend fun insert(entity: DeployJobEntity): Long

    @Query("SELECT * FROM deploy_jobs WHERE id = :id")
    suspend fun get(id: Long): DeployJobEntity?

    @Query("SELECT * FROM deploy_jobs WHERE serverId = :serverId AND state = :state ORDER BY id DESC LIMIT 1")
    suspend fun latestInState(serverId: Long, state: DeployState = DeployState.RUNNING): DeployJobEntity?

    @Query("UPDATE deploy_jobs SET remoteDir = :remoteDir, launchedAt = :launchedAt WHERE id = :id")
    suspend fun markLaunched(id: Long, remoteDir: String, launchedAt: Long)

    @Query("UPDATE deploy_jobs SET stage = :stage WHERE id = :id")
    suspend fun updateStage(id: Long, stage: String)

    @Query("UPDATE deploy_jobs SET state = :state, errorCode = :errorCode, warnings = :warnings, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, state: DeployState, errorCode: String?, warnings: String, finishedAt: Long)
}
