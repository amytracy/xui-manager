package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshSession
import dev.autorasqr.xuimanager.data.ssh.SshTarget
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.SudoMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a script from assets/deploy as root on the server, detached from the SSH connection (setsid nohup), and
 * follows it through the files in its job directory: log, stage, exit and pid. Used for deploys and for self-steal.
 */
@Singleton
class RemoteScriptRunner @Inject constructor(private val ssh: SshConnector) {

    /** Uploads [files] (name to content, mode 600) into [jobDir] and starts [script] there. */
    suspend fun launch(session: SshSession, jobDir: String, files: Map<String, ByteArray>, script: String, sudo: SudoMode) {
        files.forEach { (name, content) -> session.writePrivateFile("$jobDir/$name", content) }
        val prepared = session.execSh(RemoteJob.prepareCommand(jobDir))
        if (!prepared.ok) throw AppException(AppError.Unexpected("could not prepare $jobDir"))

        val started = session.execPrivileged(RemoteJob.startScript(jobDir, script), sudo)
        if (!started.ok) {
            throw AppException(
                if (started.stderr.contains("password is required", ignoreCase = true)) AppError.SudoPasswordRequired
                else AppError.NoRootAccess,
            )
        }
    }

    /**
     * Tails the remote log until the script writes its exit code and returns it. Survives dropped connections;
     * gives up after [timeoutMs] from [launchedAt].
     */
    suspend fun follow(
        target: SshTarget,
        jobDir: String,
        launchedAt: Long,
        timeoutMs: Long,
        onLines: (List<String>) -> Unit,
        onStage: suspend (String) -> Unit,
        onReconnect: (String) -> Unit,
    ): String {
        val assembler = LogLineAssembler()
        var offset = 0L
        var failures = 0
        val deadline = launchedAt + timeoutMs
        while (true) {
            try {
                val exitCode = ssh.withSession(target) { session ->
                    failures = 0
                    while (true) {
                        val snapshot = PollParser.parse(session.execSh(RemoteJob.pollCommand(jobDir, offset), timeoutSeconds = 30).stdout)
                        if (snapshot.noJob) throw AppException(AppError.DeployInterrupted)
                        offset += snapshot.logChunk.size
                        onLines(assembler.feed(snapshot.logChunk))
                        snapshot.stage?.let { onStage(it) }
                        snapshot.exitCode?.let { code ->
                            assembler.flush()?.let { onLines(listOf(it)) }
                            return@withSession code
                        }
                        val sinceLaunch = System.currentTimeMillis() - launchedAt
                        if (snapshot.liveness == Liveness.DEAD || (snapshot.liveness == Liveness.NOT_STARTED && sinceLaunch > 60_000)) {
                            throw AppException(AppError.DeployInterrupted)
                        }
                        if (System.currentTimeMillis() > deadline) throw AppException(AppError.Timeout)
                        delay(DeployConfig.POLL_INTERVAL_MS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
                return exitCode
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                if (error.isFatalForReconnect() || ++failures > MAX_RECONNECTS || System.currentTimeMillis() > deadline) throw e
                val wait = (failures * 5_000L).coerceAtMost(30_000L)
                onReconnect("Connection lost ($error), reconnecting in ${wait / 1000} s…")
                delay(wait)
            }
        }
    }

    /** Errors that another connection attempt can't fix. Network trouble and timeouts are retried. */
    private fun AppError.isFatalForReconnect(): Boolean =
        this == AppError.SshAuthFailed || this == AppError.InvalidPrivateKey || this == AppError.DeployInterrupted ||
            this is AppError.HostKeyMismatch || this is AppError.HostKeyUnknown

    private companion object {
        const val MAX_RECONNECTS = 20
    }
}
