package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.ui.servers.isUninstallConfirmed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the real uninstall.sh with bash against fake systemctl, ss, ufw, iptables, crontab and acme.sh placed first on
 * PATH, with every system directory redirected into a temporary one.
 */
class UninstallScriptTest {

    private val root: File = Files.createTempDirectory("xui-uninstall").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }
    private val calls = File(root, "calls").apply { writeText("") }
    private val state = File(root, "state").apply { mkdirs() }
    private val jobsRoot = File(root, "home/.xui-manager/jobs")
    private val jobDir = File(jobsRoot, "200").apply { mkdirs() }
    private val oldJob = File(jobsRoot, "100")

    private val xuiDir = File(root, "usr/local/x-ui")
    private val etc = File(root, "etc")
    private val xuiCli = File(root, "usr/bin/x-ui")
    private val xuiLog = File(root, "var/log/x-ui")
    private val units = File(root, "systemd")
    private val sbin = File(root, "usr/local/bin")
    private val ssEtc = File(etc, "xui-manager/selfsteal")
    private val ssWww = File(root, "var/www/xui-selfsteal")
    private val certRoot = File(root, "root/cert")
    private val acmeHome = File(root, "root/.acme.sh")
    private val backups = File(root, "root/xui-backup")

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

    private fun file(f: File, text: String = "x") = f.apply { parentFile?.mkdirs(); writeText(text) }

    /** Everything a deploy with self-steal, RoscomVPN geodata and a clean reinstall leaves behind. */
    private fun installEverything() {
        file(File(xuiDir, "x-ui"))
        file(File(xuiDir, "bin/geoip_roscomvpn.dat"))
        file(File(etc, "x-ui/x-ui.db"))
        file(File(etc, "default/x-ui"), "XUI_DB_TYPE=sqlite\n")
        file(File(etc, "fail2ban/jail.d/3x-ipl.conf"))
        file(File(etc, "fail2ban/filter.d/3x-ipl.conf"))
        file(File(etc, "fail2ban/jail.d/sshd.local"))
        file(xuiCli)
        file(File(xuiLog, "3xipl.log"))
        file(File(units, "x-ui.service"))
        file(File(units, "x-ui.service.d/10-xui-sandbox.conf"))
        file(File(units, "xui-caddy.service"))
        file(File(units, "xui-roscomvpn-geodata.timer"))
        file(File(units, "xui-roscomvpn-geodata.service"))
        file(File(sbin, "xui-caddy")).setExecutable(true)
        file(File(sbin, "xui-manager-cert-hook")).setExecutable(true)
        file(File(sbin, "xui-roscomvpn-geodata")).setExecutable(true)
        file(File(ssEtc, "Caddyfile"))
        file(File(ssWww, "index.html"))
        file(File(certRoot, "example.com/fullchain.pem"))
        file(File(acmeHome, "acme.sh"), "#!/bin/bash\necho \"acme.sh \$*\" >> \"${calls.absolutePath}\"\n").setExecutable(true)
        file(File(backups, "x-ui-20261020.db"))
        file(File(oldJob, "log"))
        File(state, "x-ui-active").writeText("")
        File(state, "fail2ban-active").writeText("")
        File(state, "crontab").writeText("17 3 * * * \"/root/.acme.sh\"/acme.sh --cron --home \"/root/.acme.sh\" > /dev/null\n0 5 * * * /usr/local/bin/backup\n")
    }

    /**
     * [listening] is what `ss -tulnp` shows before the services stop, [busyAfter] the TCP ports something still
     * listens on afterwards. [stopWorks] false keeps x-ui running whatever systemctl is told.
     */
    private fun run(
        ports: List<Int> = listOf(2053, 2096),
        listening: List<Pair<String, String>> = emptyList(),
        busyAfter: List<Int> = emptyList(),
        ufwActive: Boolean = true,
        stopWorks: Boolean = true,
    ): Int {
        val s = state.absolutePath
        fake("id", """case "${'$'}1" in -u|-g) echo 0 ;; *) echo "uid=0(root)" ;; esac""")
        fake("chown", "exit 0")
        fake("userdel", "exit 0")
        fake(
            "systemctl",
            """case "${'$'}1 ${'$'}2" in
                 "is-active x-ui") [ -f "$s/x-ui-active" ] ;;
                 "is-active fail2ban") [ -f "$s/fail2ban-active" ] ;;
                 "stop x-ui") ${if (stopWorks) "rm -f \"$s/x-ui-active\"" else "exit 1"} ;;
                 *) exit 0 ;;
               esac""",
        )
        val processTable = listening.joinToString("\n") { (addr, proc) -> "tcp   LISTEN 0      4096   $addr   *:*   users:((\"$proc\",pid=7,fd=3))" }
        val busyTable = busyAfter.joinToString("\n") { "LISTEN 0      4096   0.0.0.0:$it   0.0.0.0:*" }
        fake(
            "ss",
            """if [ "${'$'}1" = -tulnp ]; then
                 echo "Netid State  Recv-Q Send-Q Local Address:Port Peer Address:Port Process"
                 cat <<'EOT'
$processTable
EOT
               else
                 echo "State  Recv-Q Send-Q Local Address:Port Peer Address:Port"
                 cat <<'EOT'
$busyTable
EOT
               fi""",
        )
        fake("ufw", if (ufwActive) """[ "${'$'}1" = status ] && echo "Status: active"; exit 0""" else """[ "${'$'}1" = status ] && echo "Status: inactive"; exit 0""")
        fake("firewall-cmd", "exit 1")
        fake("iptables", "exit 1")
        fake("crontab", """if [ "${'$'}1" = -l ]; then cat "$s/crontab"; else cat > "$s/crontab"; fi""")
        listOf("lib.sh", "selfsteal.sh", "uninstall.sh").forEach { File("src/main/assets/deploy/$it").copyTo(File(jobDir, it), overwrite = true) }
        File(jobDir, "params.env").writeText(RemoteJob.uninstallEnv(1000, 1000, 2222, ports))
        listOf("log", "stage").forEach { File(jobDir, it).writeText("") }
        val process = ProcessBuilder("bash", File(jobDir, "uninstall.sh").absolutePath)
            .directory(jobDir)
            .redirectErrorStream(true)
            .apply {
                val env = environment()
                env["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                env["XUI_MANAGER_XUI_DIR"] = xuiDir.absolutePath
                env["XUI_MANAGER_ETC"] = etc.absolutePath
                env["XUI_MANAGER_XUI_CLI"] = xuiCli.absolutePath
                env["XUI_MANAGER_XUI_LOG"] = xuiLog.absolutePath
                env["XUI_MANAGER_UNIT_DIR"] = units.absolutePath
                env["XUI_MANAGER_BIN_DIR"] = sbin.absolutePath
                env["XUI_MANAGER_SS_ETC"] = ssEtc.absolutePath
                env["XUI_MANAGER_SS_WWW"] = ssWww.absolutePath
                env["XUI_MANAGER_CERT_ROOT"] = certRoot.absolutePath
                env["XUI_MANAGER_ACME_HOME"] = acmeHome.absolutePath
                env["XUI_MANAGER_BACKUP_DIR"] = backups.absolutePath
            }
            .start()
        assertTrue("uninstall.sh hung", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun exitCode() = File(jobDir, "exit").readText()
    private fun calls() = calls.readLines()
    private fun log() = File(jobDir, "log").readText()
    private fun closed() = calls().filter { it.startsWith("ufw --force delete allow ") }.map { it.removePrefix("ufw --force delete allow ") }.toSet()

    @Test
    fun `removes everything the app installed`() {
        installEverything()
        run()
        assertEquals(log(), "0", exitCode())
        listOf(
            xuiDir, File(etc, "x-ui"), File(etc, "default/x-ui"), xuiCli, xuiLog,
            File(units, "x-ui.service"), File(units, "x-ui.service.d"), File(units, "xui-caddy.service"),
            File(units, "xui-roscomvpn-geodata.timer"), File(units, "xui-roscomvpn-geodata.service"),
            File(sbin, "xui-caddy"), File(sbin, "xui-manager-cert-hook"), File(sbin, "xui-roscomvpn-geodata"),
            File(etc, "xui-manager"), ssWww, File(etc, "fail2ban/jail.d/3x-ipl.conf"), File(etc, "fail2ban/filter.d/3x-ipl.conf"),
            certRoot, acmeHome, backups, oldJob,
        ).forEach { assertFalse("${it.path} is still there", it.exists()) }
        // The user's own fail2ban jail and the running job stay.
        assertTrue(File(etc, "fail2ban/jail.d/sshd.local").exists())
        assertTrue(jobDir.exists())
        assertTrue(calls().contains("systemctl stop x-ui"))
        assertTrue(calls().contains("systemctl disable x-ui"))
        assertTrue(calls().contains("systemctl disable --now xui-caddy"))
        assertTrue(calls().contains("systemctl disable --now xui-roscomvpn-geodata.timer"))
        assertTrue(calls().contains("systemctl restart fail2ban"))
        assertTrue(calls().contains("acme.sh --uninstall"))
        assertEquals(listOf("0 5 * * * /usr/local/bin/backup"), File(state, "crontab").readLines())
        assertTrue(log().contains("The server is clean"))
    }

    @Test
    fun `closes the panel ports but never the ssh port or a port another service still uses`() {
        installEverything()
        run(
            ports = listOf(2053, 2096, 2222),
            listening = listOf(
                "*:2053" to "x-ui",
                "*:443" to "xray-linux-amd6",
                "[::]:8443" to "xray-linux-amd6",
                "127.0.0.1:62789" to "xray-linux-amd6",
                "0.0.0.0:2222" to "sshd",
                "0.0.0.0:2200" to "sshd",
                "0.0.0.0:8080" to "nginx",
            ),
            busyAfter = listOf(80, 2200, 2222, 8080),
        )
        assertEquals(log(), "0", exitCode())
        assertEquals(setOf("2053/tcp", "2096/tcp", "443/tcp", "8443/tcp"), closed())
        assertTrue(log().contains("Port 80 is still in use by another service"))
    }

    @Test
    fun `a clean server is not an error`() {
        run(ufwActive = false)
        assertEquals(log(), "0", exitCode())
        assertTrue(closed().isEmpty())
        assertFalse(calls().contains("systemctl stop x-ui"))
        assertTrue(log().contains("No decoy site"))
    }

    @Test
    fun `fails when x-ui keeps running`() {
        installEverything()
        run(stopWorks = false)
        assertEquals("E_UNINSTALL", exitCode())
        assertEquals(AppError.UninstallFailed, DeployExitCodes.toAppError(exitCode()))
        assertTrue(xuiDir.exists())
    }

    @Test
    fun `params env lists the ports once and in order`() {
        val env = RemoteJob.uninstallEnv(0, 0, 22, listOf(443, 2053, 443, 0, 70000))
        assertTrue(env, env.contains("PORTS='443:2053'\n"))
        assertTrue(env, env.contains("SSH_PORT='22'\n"))
        assertEquals("rm -rf '/home/u/.xui-manager'", RemoteJob.removeJobsCommand("/home/u/"))
    }

    @Test
    fun `confirmation needs the word yes`() {
        assertTrue(isUninstallConfirmed("yes"))
        assertTrue(isUninstallConfirmed(" Yes "))
        assertFalse(isUninstallConfirmed(""))
        assertFalse(isUninstallConfirmed("y"))
        assertFalse(isUninstallConfirmed("yes please"))
    }
}
