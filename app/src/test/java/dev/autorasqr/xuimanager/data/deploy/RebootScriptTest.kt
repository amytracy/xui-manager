package dev.autorasqr.xuimanager.data.deploy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the real reboot.sh with bash against fake id, systemd-run and shutdown placed first on PATH. */
class RebootScriptTest {

    private val root: File = Files.createTempDirectory("xui-reboot").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }
    private val jobDir = File(root, "job").apply { mkdirs() }
    private val calls = File(root, "calls").apply { writeText("") }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fake(name: String, body: String) {
        File(bin, name).apply {
            writeText("#!/bin/bash\necho \"$name \$*\" >> \"${calls.absolutePath}\"\n$body\n")
            setExecutable(true)
        }
    }

    private fun run(uid: Int = 0, systemdRun: String = "exit 0", shutdown: String = "exit 0") {
        fake("id", """case "${'$'}1" in -u|-g) echo $uid ;; *) echo "uid=$uid" ;; esac""")
        fake("chown", "exit 0")
        fake("systemctl", "exit 0")
        fake("systemd-run", systemdRun)
        fake("shutdown", shutdown)
        listOf("lib.sh", "reboot.sh").forEach { File("src/main/assets/deploy/$it").copyTo(File(jobDir, it), overwrite = true) }
        File(jobDir, "params.env").writeText(RemoteJob.rebootEnv(1000, 1000))
        listOf("log", "stage").forEach { File(jobDir, it).writeText("") }
        val process = ProcessBuilder("bash", File(jobDir, "reboot.sh").absolutePath)
            .directory(jobDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["XUI_MANAGER_REBOOT_DELAY"] = "0"
            }
            .start()
        assertTrue("reboot.sh hung", process.waitFor(30, TimeUnit.SECONDS))
    }

    private fun exitCode() = File(jobDir, "exit").readText()
    private fun calls() = calls.readLines()

    @Test
    fun `schedules the restart with a systemd timer`() {
        run()
        assertEquals("0", exitCode())
        assertTrue(calls().toString(), calls().any { it.startsWith("systemd-run --on-active=0") && it.endsWith("systemctl reboot") })
        assertTrue(calls().none { it.startsWith("shutdown") })
    }

    @Test
    fun `falls back to a detached shutdown`() {
        run(systemdRun = "exit 1")
        assertEquals("0", exitCode())
        // The detached sleep-then-shutdown runs after the script has finished.
        val deadline = System.currentTimeMillis() + 10_000
        while (calls().none { it.startsWith("shutdown -r now") } && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(calls().toString(), calls().any { it.startsWith("shutdown -r now") })
    }

    @Test
    fun `needs root`() {
        run(uid = 1000)
        assertEquals("E_NOT_ROOT", exitCode())
        assertTrue(calls().none { it.startsWith("systemd-run") || it.startsWith("shutdown") })
    }
}
