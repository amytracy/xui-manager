package dev.autorasqr.xuimanager.data.deploy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the real sysupdate.sh with bash against fake apt-get, dpkg, shutdown and systemctl placed first on PATH. */
class SysUpdateScriptTest {

    private val root: File = Files.createTempDirectory("xui-sysupdate").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }
    private val jobDir = File(root, "job").apply { mkdirs() }
    private val calls = File(root, "calls").apply { writeText("") }
    private val rebootFlag = File(root, "reboot-required")

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

    /** [apt] is the body of the fake apt-get; [rebootAfterUpgrade] makes the upgrade leave the reboot flag behind. */
    private fun run(apt: String = "exit 0", rebootAfterUpgrade: Boolean = false, shutdown: String = "exit 0"): Int {
        fake("id", """case "${'$'}1" in -u|-g) echo 0 ;; *) echo "uid=0(root)" ;; esac""")
        fake("chown", "exit 0")
        fake("systemctl", "exit 0")
        fake("systemd-run", "exit 1")
        fake("dpkg", "exit 0")
        fake("shutdown", shutdown)
        fake(
            "apt-get",
            """for a in "${'$'}@"; do [ "${'$'}a" = upgrade ] && ${if (rebootAfterUpgrade) "touch \"${rebootFlag.absolutePath}\"" else ":"}; done
               $apt""",
        )
        listOf("lib.sh", "sysupdate.sh").forEach { File("src/main/assets/deploy/$it").copyTo(File(jobDir, it), overwrite = true) }
        File(jobDir, "params.env").writeText(RemoteJob.sysUpdateEnv(1000, 1000))
        listOf("log", "stage").forEach { File(jobDir, it).writeText("") }
        val process = ProcessBuilder("bash", File(jobDir, "sysupdate.sh").absolutePath)
            .directory(jobDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["XUI_MANAGER_REBOOT_FLAG"] = rebootFlag.absolutePath
            }
            .start()
        assertTrue("sysupdate.sh hung", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun exitCode() = File(jobDir, "exit").readText()
    private fun result() = parseKeyValues(File(jobDir, "result.env").readText())
    private fun calls() = calls.readLines()

    @Test
    fun `updates and upgrades without questions and keeps changed config files`() {
        run()
        assertEquals("0", exitCode())
        assertEquals("0", result()["REBOOT"])
        val apt = calls().filter { it.startsWith("apt-get") }
        assertTrue(apt[0], apt[0].endsWith(" update"))
        assertTrue(apt[1], apt[1].contains("upgrade --with-new-pkgs"))
        assertTrue(apt[1], apt[1].contains("--force-confold") && apt[1].contains("-y"))
        assertTrue(calls().contains("dpkg --configure -a"))
        assertFalse(calls().any { it.startsWith("shutdown") })
        assertTrue(File(jobDir, "log").readText().contains("No restart needed"))
    }

    @Test
    fun `schedules a reboot a minute ahead when the upgrade asks for one`() {
        run(rebootAfterUpgrade = true)
        assertEquals("0", exitCode())
        assertEquals("1", result()["REBOOT"])
        assertTrue(calls().any { it.startsWith("shutdown -r +1") })
    }

    @Test
    fun `a reboot that cannot be scheduled is reported`() {
        run(rebootAfterUpgrade = true, shutdown = "exit 1")
        assertEquals("E_SYSUPDATE:reboot", exitCode())
    }

    @Test
    fun `a failing upgrade stops before any reboot`() {
        run(apt = """for a in "${'$'}@"; do [ "${'$'}a" = upgrade ] && { echo "E: broken packages"; exit 100; }; done; exit 0""")
        assertEquals("E_SYSUPDATE", exitCode())
        assertFalse(File(jobDir, "result.env").exists())
        assertFalse(calls().any { it.startsWith("shutdown") })
    }

    @Test
    fun `a lock held by another package manager has its own error`() {
        run(apt = """echo "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 812 (unattended-upgr)"; exit 100""")
        assertEquals("E_SYSUPDATE:lock", exitCode())
    }
}
