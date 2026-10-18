package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.DeployOptions
import dev.autorasqr.xuimanager.domain.model.DeployProgress
import dev.autorasqr.xuimanager.domain.model.PrecheckResult
import kotlinx.coroutines.flow.Flow

interface DeployRepository {
    /** [checkDomain]: also make sure the server's domain points at it (needed before Let's Encrypt). */
    suspend fun precheck(serverId: Long, checkDomain: Boolean = true): PrecheckResult

    /** Creates the job and starts it in the foreground service. Returns the job id. */
    suspend fun startDeploy(serverId: Long, options: DeployOptions, precheck: PrecheckResult): Long

    /** Re-attaches the foreground service to a job whose remote script may still be running. */
    suspend fun resume(jobId: Long)

    fun observeProgress(jobId: Long): Flow<DeployProgress?>

    /** Latest job for the server that has not finished, or null. */
    suspend fun unfinishedJob(serverId: Long): Long?

    fun isAttached(jobId: Long): Boolean
}
