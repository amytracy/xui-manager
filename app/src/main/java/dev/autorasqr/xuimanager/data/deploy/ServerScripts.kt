package dev.autorasqr.xuimanager.data.deploy

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.autorasqr.xuimanager.data.ssh.SshConnector
import dev.autorasqr.xuimanager.data.ssh.SshTargets
import dev.autorasqr.xuimanager.data.ssh.shQuote
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.PrecheckResult
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import javax.inject.Inject
import javax.inject.Singleton

/** One-off maintenance scripts from assets/deploy (selfsteal.sh, geodata.sh, uninstall.sh…), run as root and followed like a deploy. */
@Singleton
class ServerScripts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deployRepository: DeployRepository,
    private val ssh: SshConnector,
    private val targets: SshTargets,
    private val runner: RemoteScriptRunner,
) {
    /**
     * Uploads lib.sh and [script] with the params.env [params] builds from the precheck, runs it and returns the
     * values of its result.env (empty if it writes none). A non-zero exit becomes the matching AppException.
     * [extraFiles] are uploaded into the job directory next to the script, and so are the [libs] from assets/deploy
     * it sources. With [removeJobsAfter] a successful run takes the app's whole job directory away instead of only
     * the secrets in its own one.
     */
    suspend fun run(
        server: Server,
        script: String,
        checkDomain: Boolean,
        timeoutMs: Long,
        params: (PrecheckResult) -> String,
        log: (String) -> Unit,
        extraFiles: Map<String, ByteArray> = emptyMap(),
        libs: List<String> = emptyList(),
        removeJobsAfter: Boolean = false,
    ): Map<String, String> {
        log("Checking the server" + if (checkDomain && server.domain != null) " and that ${server.domain} points at it" else "")
        val precheck = deployRepository.precheck(server.id, checkDomain)
        val target = targets.forServer(server)
        val jobDir = RemoteJob.jobDir(precheck.home, System.currentTimeMillis())
        val files = (listOf("lib.sh", script) + libs).associateWith { name -> context.assets.open("deploy/$name").use { it.readBytes() } } +
            extraFiles + ("params.env" to params(precheck).toByteArray())
        val launchedAt = System.currentTimeMillis()
        ssh.withSession(target) { session -> runner.launch(session, jobDir, files, script, precheck.sudo) }
        log("Started on the server")
        val exit = runner.follow(
            target = target,
            jobDir = jobDir,
            launchedAt = launchedAt,
            timeoutMs = timeoutMs,
            onLines = { lines -> lines.forEach(log) },
            onStage = {},
            onReconnect = log,
        )
        try {
            if (exit != "0") throw AppException(DeployExitCodes.toAppError(exit))
            val result = ssh.withSession(target) { it.execSh("cd ${shQuote(jobDir)} && cat result.env 2>/dev/null || true") }
            return parseKeyValues(result.stdoutText)
        } finally {
            val cleanup = if (removeJobsAfter && exit == "0") RemoteJob.removeJobsCommand(precheck.home) else RemoteJob.cleanupCommand(jobDir)
            runCatching { ssh.withSession(target) { it.execSh(cleanup) } }
        }
    }
}
