package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.SudoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteJobTest {

    private val params = StoredDeployParams(
        mode = DeployMode.ONE_CLICK,
        sni = null,
        existingAction = ExistingInstallAction.UPDATE,
        existingInstalled = true,
        sudo = SudoMode.ROOT,
        uid = 0,
        gid = 0,
        home = "/root",
        sslMode = "ip",
        panelUsername = "admin",
        panelPort = 34567,
        subPort = 45678,
        inboundPort = 443,
        inboundPortStrict = false,
    )

    @Test
    fun `params env is single-quoted and complete`() {
        val env = RemoteJob.paramsEnv(params, "203.0.113.7")
        assertTrue(env.contains("SERVER_HOST='203.0.113.7'\n"))
        assertTrue(env.contains("EXISTING_ACTION='update'\n"))
        assertTrue(env.contains("INSTALLER_SHA256='${DeployConfig.INSTALLER_SHA256}'\n"))
        assertTrue(env.contains("INBOUND_PORT_STRICT='0'\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `values with shell metacharacters are rejected`() {
        RemoteJob.secretsEnv("admin", "pa\$s'word", "path")
    }

    @Test
    fun `job dir lives under the login home`() {
        assertEquals("/home/u/.xui-manager/jobs/7", RemoteJob.jobDir("/home/u/", 7))
    }

    @Test
    fun `cleanup removes the uploaded decoy page with the other job files`() {
        assertTrue(RemoteJob.cleanupCommand("/root/job").contains(" ${DeployConfig.SITE_PAGE_FILE} "))
    }

    /** Runs the real snippets with the local /bin/sh against a fake job directory. */
    @Test
    fun `prepare and poll commands work in a POSIX shell`() {
        val home = Files.createTempDirectory("xui-home").toFile()
        val jobDir = File(RemoteJob.jobDir(home.absolutePath, 1)).apply { mkdirs() }
        File(jobDir, "exit").writeText("stale")

        assertEquals(0, sh(RemoteJob.prepareCommand(jobDir.absolutePath)).first)
        assertTrue(File(jobDir, "log").exists())
        assertTrue(!File(jobDir, "exit").exists())

        File(jobDir, "stage").writeText("INSTALL")
        File(jobDir, "log").writeText("first\nsecond\n")
        val first = PollParser.parse(sh(RemoteJob.pollCommand(jobDir.absolutePath, 0)).second)
        assertEquals("INSTALL", first.stage)
        assertEquals(Liveness.NOT_STARTED, first.liveness)
        assertEquals("first\nsecond\n", first.logChunk.decodeToString())

        File(jobDir, "log").appendText("third\n")
        File(jobDir, "exit").writeText("0")
        val second = PollParser.parse(sh(RemoteJob.pollCommand(jobDir.absolutePath, first.logChunk.size.toLong())).second)
        assertEquals("0", second.exitCode)
        assertEquals("third\n", second.logChunk.decodeToString())

        val missing = PollParser.parse(sh(RemoteJob.pollCommand(jobDir.absolutePath + "-gone", 0)).second)
        assertTrue(missing.noJob)

        assertTrue(sh(RemoteJob.runnerAliveCommand(jobDir.absolutePath)).first != 0)

        // Process.pid() is not in the Android API the tests compile against, so let the shell report it.
        val sleeperPid = sh("sleep 30 >/dev/null 2>&1 & echo \$!").second.decodeToString().trim()
        try {
            File(jobDir, "exit").delete()
            File(jobDir, "pid").writeText(sleeperPid)
            assertEquals(Liveness.ALIVE, PollParser.parse(sh(RemoteJob.pollCommand(jobDir.absolutePath, 0)).second).liveness)
            assertEquals(0, sh(RemoteJob.runnerAliveCommand(jobDir.absolutePath)).first)
        } finally {
            sh("kill $sleeperPid")
        }
        File(jobDir, "pid").writeText("999999999")
        assertEquals(Liveness.DEAD, PollParser.parse(sh(RemoteJob.pollCommand(jobDir.absolutePath, 0)).second).liveness)
        assertTrue(sh(RemoteJob.runnerAliveCommand(jobDir.absolutePath)).first != 0)
        assertTrue(sh(RemoteJob.runnerAliveCommand(jobDir.absolutePath + "-gone")).first != 0)
        home.deleteRecursively()
    }

    private fun sh(script: String): Pair<Int, ByteArray> {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(false).start()
        val output = process.inputStream.readBytes()
        return process.waitFor() to output
    }
}
