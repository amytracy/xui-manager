package dev.autorasqr.xuimanager.e2e

import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A fake VPS for watching the app work on an emulator, started by hand and never part of the normal test run:
 *
 *     XUI_STAND_DIR=/some/dir ./gradlew :app:testDebugUnitTest --tests '*EmulatorStand*'
 *
 * The directory must contain ca.pem (test CA certificate + PKCS#8 key) whose certificate is built into the `e2e`
 * app variant. SSH listens on 0.0.0.0:2222 (root / stand); every command runs in /bin/sh on this machine with fake
 * system tools first on PATH, so the real run.sh and selfsteal.sh execute. The fake 3x-ui panel starts on whatever
 * port the deploy picks, with a certificate for 10.0.2.2 and [DOMAIN]. Create `stop` in the directory to end it.
 */
class EmulatorStand {

    @Test
    fun run() {
        val dirName = System.getenv("XUI_STAND_DIR")
        assumeTrue("set XUI_STAND_DIR to start the emulator stand", !dirName.isNullOrBlank())
        Stand(File(dirName!!)).runUntilStopped()
    }

    private class Stand(private val dir: File) {
        private val bin = File(dir, "bin")
        private val home = File(dir, "root")
        private val logFile = File(dir, "stand.log")
        private val ca = HeldCertificate.decode(File(dir, "ca.pem").readText())
        private val certificate = HeldCertificate.Builder()
            .signedBy(ca)
            .commonName(DOMAIN)
            .addSubjectAlternativeName("10.0.2.2")
            .addSubjectAlternativeName(DOMAIN)
            .addSubjectAlternativeName("localhost")
            .duration(7, TimeUnit.DAYS)
            .build()
        private val tls = HandshakeCertificates.Builder().heldCertificate(certificate, ca.certificate).build()
        private val panel = FakePanel("tok-e2e", ::log)
        private var panelServer: MockWebServer? = null
        private var panelPort = 0

        fun runUntilStopped() {
            prepare()
            val sshd = SshServer.setUpDefaultServer().apply {
                host = "0.0.0.0"
                port = SSH_PORT
                keyPairProvider = SimpleGeneratorHostKeyProvider(File(dir, "hostkey.ser").toPath())
                passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "root" && password == "stand" }
                commandFactory = CommandFactory { channel, command ->
                    log("ssh $ ${command.take(160).replace('\n', ' ')}")
                    ProcessShellFactory(command, "/bin/bash", "-c", envPrefix() + command).createShell(channel)
                }
            }
            sshd.start()
            log("SSH on 0.0.0.0:$SSH_PORT (root / stand), domain $DOMAIN")
            val stop = File(dir, "stop").apply { delete() }
            val deadline = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4)
            try {
                while (!stop.exists() && System.currentTimeMillis() < deadline) {
                    syncPanel()
                    Thread.sleep(300)
                }
            } finally {
                sshd.stop(true)
                panelServer?.close()
                log("stopped")
            }
        }

        /** Fresh server: no 3x-ui yet, only SSH on port 22 "listening". */
        private fun prepare() {
            listOf("x-ui", "certs", "ss-etc", "ss-www", "ss-bin", "ss-units", "root", "bin").forEach { File(dir, it).deleteRecursively() }
            listOf("panel-port", "panel-cert", "panel-username", "panel-password", "trusted", "calls").forEach { File(dir, it).delete() }
            bin.mkdirs()
            home.mkdirs()
            File(dir, "ss-units").mkdirs()
            File(dir, "listening").writeText("22\n")
            File(dir, "calls").writeText("")
            val replacements = mapOf(
                "@STAND@" to dir.absolutePath,
                "@CADDY_SHA@" to DeployConfig.CADDY_SHA256_AMD64,
                "@INSTALLER_SHA@" to DeployConfig.INSTALLER_SHA256,
                "@CADDY_VERSION@" to DeployConfig.CADDY_VERSION,
            )
            SCRIPTS.forEach { name ->
                val text = javaClass.getResourceAsStream("/e2e/$name")!!.bufferedReader().readText()
                    .let { replacements.entries.fold(it) { acc, (k, v) -> acc.replace(k, v) } }
                val target = when (name) {
                    "installer.sh", "fake-x-ui", "acme.sh", "fake-caddy" -> File(dir, name)
                    else -> File(bin, name)
                }
                target.writeText(text)
                target.setExecutable(true)
            }
            val staging = File(dir, "caddy-staging").apply { deleteRecursively(); mkdirs() }
            File(dir, "fake-caddy").copyTo(File(staging, "caddy"), overwrite = true).setExecutable(true)
            ProcessBuilder("tar", "-czf", File(dir, "caddy.tar.gz").absolutePath, "-C", staging.absolutePath, "caddy")
                .start().waitFor(30, TimeUnit.SECONDS)
        }

        private fun envPrefix(): String {
            val vars = mapOf(
                "HOME" to home.absolutePath,
                "PATH" to "${bin.absolutePath}:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin",
                "XUI_MANAGER_XUI_DIR" to File(dir, "x-ui").absolutePath,
                "XUI_MANAGER_CERT_ROOT" to File(dir, "certs").absolutePath,
                "XUI_MANAGER_ACME" to File(dir, "acme.sh").absolutePath,
                "XUI_MANAGER_SS_ETC" to File(dir, "ss-etc").absolutePath,
                "XUI_MANAGER_SS_WWW" to File(dir, "ss-www").absolutePath,
                "XUI_MANAGER_BIN_DIR" to File(dir, "ss-bin").absolutePath,
                "XUI_MANAGER_UNIT_DIR" to File(dir, "ss-units").absolutePath,
            )
            return vars.entries.joinToString("") { (k, v) -> "export $k='$v'; " } + "cd \"\$HOME\"; "
        }

        /** Starts the fake panel on the port the deploy configured, and keeps its login in sync. */
        private fun syncPanel() {
            File(dir, "panel-username").takeIf { it.exists() }?.let { panel.username = it.readText().trim() }
            File(dir, "panel-password").takeIf { it.exists() }?.let { panel.password = it.readText().trim() }
            val port = File(dir, "panel-port").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return
            if (port == panelPort) return
            panelServer?.close()
            panelServer = MockWebServer().apply {
                dispatcher = panel
                useHttps(tls.sslSocketFactory())
                start(InetAddress.getByName("0.0.0.0"), port)
            }
            panelPort = port
            log("fake 3x-ui panel on https://$DOMAIN:$port/")
        }

        @Synchronized
        fun log(line: String) {
            val text = "${java.time.LocalTime.now().withNano(0)} $line"
            println(text)
            logFile.appendText(text + "\n")
        }
    }

    private companion object {
        const val SSH_PORT = 2222
        const val DOMAIN = "vpn.10-0-2-2.sslip.io"
        val SCRIPTS = listOf(
            "id", "chown", "useradd", "socat", "ufw", "firewall-cmd", "iptables", "systemctl", "ss", "sha256sum",
            "uname", "getent", "ip", "openssl", "curl", "installer.sh", "fake-x-ui", "acme.sh", "fake-caddy",
        )
    }
}
