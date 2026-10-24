package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.SudoMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the real run.sh end to end with bash, against fake x-ui, systemctl, ss, curl, openssl and friends placed
 * first on PATH. It checks the control flow, the files the app reads back and that no secret reaches the log.
 */
class RunScriptTest {

    private val root: File = Files.createTempDirectory("xui-run").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }
    private val xuiDir = File(root, "x-ui")
    private val certRoot = File(root, "certs")
    private val trusted = File(root, "trusted")
    private val acmeFails = File(root, "acme-fails")
    private val panelCert = File(root, "panel-cert")
    private val jobDir = File(root, "job").apply { mkdirs() }
    private val listening = File(root, "listening").apply { writeText("22\n") }
    private val calls = File(root, "calls").apply { writeText("") }
    private val ssEtc = File(root, "ss-etc")
    private val ssWww = File(root, "ss-www")
    private val ssBin = File(root, "ss-bin")
    private val ssUnits = File(root, "ss-units").apply { mkdirs() }
    private val caddyTar = File(root, "caddy.tar.gz")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fake(name: String, body: String) {
        File(bin, name).apply {
            writeText("#!/bin/bash\n$body\n")
            setExecutable(true)
        }
    }

    /** A fake x-ui CLI; [withTokens] selects 3.x behaviour (API tokens) or 2.x. */
    private fun fakeXui(version: String, withTokens: Boolean) = """
        #!/bin/bash
        echo "x-ui ${'$'}*" >> "${calls.absolutePath}"
        case "${'$'}1" in
          -v) echo "$version" ;;
          cert) echo "${'$'}3 ${'$'}5" > "${panelCert.absolutePath}"; exit 0 ;;
          setting)
            shift
            if [ "${'$'}1" = "-getCert" ]; then
              if [ -s "${panelCert.absolutePath}" ]; then read -r c k < "${panelCert.absolutePath}"; echo "cert: ${'$'}c"; echo "key: ${'$'}k"; else echo "cert: "; echo "key: "; fi
              exit 0
            fi
            if [ "${'$'}1" = "-h" ]; then
              # Like Go's flag package: the usage is written line by line, and the real one is long enough that a
              # reader quitting early (grep -q) kills the writer with SIGPIPE.
              echo "Usage of setting:" >&2
              ${if (withTokens) "echo '  -getApiToken  Print an API token' >&2" else ":"}
              sleep 0.3
              for i in $(seq 1 3000); do echo "  -flag${'$'}i  description of flag ${'$'}i" >&2; done
              exit 0
            fi
            if [ "${'$'}1" = "-show" ]; then
              printf 'current panel settings as follows:\nusername: olduser\npassword: oldpass\nport: 2053\nwebBasePath: /old/\n'
              exit 0
            fi
            if [ "${'$'}1" = "-getApiToken" ]; then echo "apiToken: tok-secret-123"; exit 0; fi
            while [ ${'$'}# -gt 0 ]; do
              if [ "${'$'}1" = "-port" ]; then echo "${'$'}2" >> "${listening.absolutePath}"; fi
              shift
            done
            echo "set username and password success"
            ;;
        esac
    """.trimIndent()

    private fun installFakes(installerSha: String) {
        fake("id", """case "${'$'}1" in -u|-g) echo 0 ;; *) echo "uid=0(root)" ;; esac""")
        fake("chown", "exit 0")
        // Starting the decoy site makes it listen on its local port.
        fake(
            "systemctl",
            """echo "systemctl ${'$'}*" >> "${calls.absolutePath}"
               [ "${'$'}*" = "restart xui-caddy" ] && echo 8443 >> "${listening.absolutePath}"
               exit 0""",
        )
        fake("uname", """if [ "${'$'}1" = -m ]; then echo x86_64; else /usr/bin/uname "${'$'}@"; fi""")
        fake("ufw", "exit 1")
        fake("firewall-cmd", "exit 1")
        fake("iptables", "exit 1")
        fake(
            "ss",
            """echo "State Recv-Q Send-Q Local Address:Port Peer Address:Port"
               while read -r p; do [ -n "${'$'}p" ] && echo "LISTEN 0 4096 0.0.0.0:${'$'}p 0.0.0.0:*"; done < "${listening.absolutePath}"""",
        )
        fake(
            "sha256sum",
            """case "${'$'}1" in
                 *caddy.tar.gz) echo "${DeployConfig.CADDY_SHA256_AMD64}  ${'$'}1" ;;
                 *install.sh) echo "$installerSha  ${'$'}1" ;;
                 *) shasum -a 256 "${'$'}@" ;;
               esac""",
        )
        fake(
            "curl",
            """out=""; url=""; probe=0
               while [ ${'$'}# -gt 0 ]; do
                 case "${'$'}1" in -o) out="${'$'}2" ;; -w) probe=1 ;; https://*) url="${'$'}1" ;; esac
                 shift
               done
               echo "curl ${'$'}url" >> "${calls.absolutePath}"
               if [ "${'$'}probe" = 1 ]; then printf 2; exit 0; fi
               case "${'$'}url" in *caddyserver*) cp "${caddyTar.absolutePath}" "${'$'}out"; exit 0 ;; esac
               case "${'$'}url" in
                 */releases/latest/download/*.sha256)
                   f=${'$'}(basename "${'$'}url" .sha256)
                   if [ -f "${root.absolutePath}/geo-bad-checksum" ]; then h=${"0".repeat(64)}; else h=${'$'}(printf 'data-%s' "${'$'}f" | shasum -a 256 | awk '{print ${'$'}1}'); fi
                   echo "${'$'}h  release/${'$'}f" > "${'$'}out"; exit 0 ;;
                 */releases/latest/download/*) printf 'data-%s' "${'$'}(basename "${'$'}url")" > "${'$'}out"; exit 0 ;;
               esac
               cat > "${'$'}out" <<'INSTALLER'
mkdir -p "${'$'}XUI_MANAGER_XUI_DIR/bin"
cp "${'$'}FAKE_XUI" "${'$'}XUI_MANAGER_XUI_DIR/x-ui"
chmod +x "${'$'}XUI_MANAGER_XUI_DIR/x-ui"
printf '#!/bin/bash\necho "PrivateKey: WFp1u_7Hgn8YWkRCMZ0X6hAEUJh-yDu3o5eAG4MOLWo"\necho "Password: Xo3s7ZGOX8HWB0bHtz1bplE0g0ECpzRXJ5o5Qp5b0Fs"\n' > "${'$'}XUI_MANAGER_XUI_DIR/bin/xray-linux-amd64"
chmod +x "${'$'}XUI_MANAGER_XUI_DIR/bin/xray-linux-amd64"
printf '\033[0;32mUsername:    %s\033[0m\n' "${'$'}XUI_USERNAME"
printf 'Password:    %s\n' "${'$'}XUI_PASSWORD"
printf 'WebBasePath: %s\n' "${'$'}XUI_WEB_BASE_PATH"
echo "API Token:   install-token-xyz"
echo "x-ui ${'$'}1 installation finished, it is running now..."
INSTALLER""",
        )
        fake(
            "openssl",
            """case "${'$'}1" in
                 x509) case "${'$'}*" in *-checkend*) exit 0 ;; *-check*) echo "IP address does match certificate" ;; esac ;;
                 verify) [ -f "${trusted.absolutePath}" ] && exit 0 || exit 2 ;;
               esac""",
        )
        fake("socat", "exit 0")
        File(root, "acme.sh").apply {
            writeText(
                """#!/bin/bash
                echo "acme ${'$'}*" >> "${calls.absolutePath}"
                case "${'$'}*" in
                  *--issue*) [ -f "${acmeFails.absolutePath}" ] && exit 1; exit 0 ;;
                  *--installcert*)
                    while [ ${'$'}# -gt 0 ]; do
                      case "${'$'}1" in --key-file) echo key > "${'$'}2" ;; --fullchain-file) echo chain > "${'$'}2" ;; esac
                      shift
                    done
                    touch "${trusted.absolutePath}" ;;
                esac
                """.trimIndent(),
            )
            setExecutable(true)
        }
    }

    /** A fake Caddy release archive: `caddy version` and `caddy validate` succeed. */
    private fun fakeCaddyRelease() {
        val staging = File(root, "caddy-staging").apply { mkdirs() }
        File(staging, "caddy").apply {
            writeText("#!/bin/bash\necho \"caddy ${'$'}*\" >> \"${calls.absolutePath}\"\n[ \"${'$'}1\" = version ] && echo \"v${DeployConfig.CADDY_VERSION} h1:fake\"\nexit 0\n")
            setExecutable(true)
        }
        val tar = ProcessBuilder("tar", "-czf", caddyTar.absolutePath, "-C", staging.absolutePath, "caddy").start()
        assertTrue(tar.waitFor(30, TimeUnit.SECONDS) && tar.exitValue() == 0)
    }

    private fun copyScripts() {
        DeployConfig.SCRIPTS.forEach { File("src/main/assets/deploy/$it").copyTo(File(jobDir, it), overwrite = true) }
    }

    private fun writeJob(
        action: ExistingInstallAction?,
        inboundPort: Int = 443,
        strict: Boolean = false,
        host: String = "203.0.113.7",
        selfSteal: Boolean = false,
    ) {
        copyScripts()
        val params = StoredDeployParams(
            mode = DeployMode.ONE_CLICK,
            sni = null,
            existingAction = action,
            existingInstalled = action != null,
            sudo = SudoMode.ROOT,
            uid = 0,
            gid = 0,
            home = root.absolutePath,
            sslMode = "ip",
            panelUsername = "user12345",
            panelPort = 34567,
            subPort = 45678,
            inboundPort = inboundPort,
            inboundPortStrict = strict,
            selfSteal = selfSteal,
        ).let { if (host != "203.0.113.7") it.copy(sslMode = "domain") else it }
        File(jobDir, "params.env").writeText(RemoteJob.paramsEnv(params, host))
        File(jobDir, "secrets.env").writeText(RemoteJob.secretsEnv("user12345", "PanelPass123", "BasePath123"))
        File(jobDir, "log").writeText("")
        File(jobDir, "stage").writeText("")
    }

    private fun run(script: String = "run.sh"): Int {
        val process = ProcessBuilder("bash", File(jobDir, script).absolutePath)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["XUI_MANAGER_XUI_DIR"] = xuiDir.absolutePath
                environment()["XUI_MANAGER_CERT_ROOT"] = certRoot.absolutePath
                environment()["XUI_MANAGER_ACME"] = File(root, "acme.sh").absolutePath
                environment()["FAKE_XUI"] = File(root, "fake-x-ui").absolutePath
                environment()["XUI_MANAGER_SS_ETC"] = ssEtc.absolutePath
                environment()["XUI_MANAGER_SS_WWW"] = ssWww.absolutePath
                environment()["XUI_MANAGER_BIN_DIR"] = ssBin.absolutePath
                environment()["XUI_MANAGER_UNIT_DIR"] = ssUnits.absolutePath
            }
            .start()
        process.inputStream.readBytes()
        assertTrue("$script hung", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun result(): Map<String, String> = parseKeyValues(File(jobDir, "result.env").readText())

    @Test
    fun `fresh install runs every stage and keeps secrets out of the log`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        writeJob(action = null)

        assertEquals(0, run())
        assertEquals("0", File(jobDir, "exit").readText())
        assertEquals("DONE", File(jobDir, "stage").readText())
        assertFalse("secrets.env must be deleted", File(jobDir, "secrets.env").exists())
        assertFalse("installer must be deleted", File(jobDir, "install.sh").exists())

        val result = result()
        assertEquals("34567", result["PANEL_PORT"])
        assertEquals("45678", result["SUB_PORT"])
        assertEquals("443", result["INBOUND_PORT"])
        assertEquals(File(certRoot, "ip/fullchain.pem").absolutePath, result["SUB_CERT"])
        assertTrue(calls.readText().contains("acme --issue -d 203.0.113.7 --standalone --server letsencrypt --certificate-profile shortlived"))
        assertEquals(File(certRoot, "ip/fullchain.pem").absolutePath + " " + File(certRoot, "ip/privkey.pem").absolutePath, panelCert.readText().trim())
        assertEquals("tok-secret-123", result["API_TOKEN"])
        assertEquals("3.10.2", result["PANEL_VERSION"])
        assertEquals(null, result["WEB_BASE_PATH"])
        assertTrue(File(jobDir, "x25519.out").readText().contains("PrivateKey"))

        val log = File(jobDir, "log").readText()
        listOf("PanelPass123", "BasePath123", "user12345", "tok-secret-123", "install-token-xyz").forEach { secret ->
            assertFalse("log leaks $secret:\n$log", log.contains(secret))
        }
        assertFalse("ANSI colours must be stripped", log.contains("\u001b["))
        assertTrue(log.contains("==> INSTALL"))
        assertTrue(calls.readText().contains("setting -username user12345 -password PanelPass123 -port 34567 -webBasePath BasePath123"))
    }

    @Test
    fun `adopting a 2_x panel keeps its port and path and skips the installer`() {
        installFakes("does-not-matter")
        File(root, "fake-x-ui").writeText(fakeXui("2.6.7", withTokens = false))
        xuiDir.mkdirs()
        File(root, "fake-x-ui").copyTo(File(xuiDir, "x-ui")).setExecutable(true)
        listening.appendText("2053\n")
        File(root, "existing-cert").writeText("chain")
        panelCert.writeText(File(root, "existing-cert").absolutePath + " key\n")
        trusted.writeText("")
        writeJob(action = ExistingInstallAction.ADOPT)

        assertEquals(0, run())
        assertEquals("0", File(jobDir, "exit").readText())
        val result = result()
        assertEquals("2053", result["PANEL_PORT"])
        assertEquals("/old/", result["WEB_BASE_PATH"])
        assertEquals("", result["API_TOKEN"])
        assertEquals("2.6.7", result["PANEL_VERSION"])
        assertEquals("1", result["EXISTED"])
        assertFalse("a trusted certificate must not be re-issued", calls.readText().contains("acme --issue"))
        assertFalse(File(jobDir, "x25519.out").exists())

        val log = File(jobDir, "log").readText()
        assertFalse(log.contains("==> INSTALL"))
        assertFalse("old password from -show must never be logged", log.contains("oldpass"))
        assertFalse(log.contains("PanelPass123"))
        val setting = calls.readText().lines().single { it.startsWith("x-ui setting -username") }
        assertEquals("x-ui setting -username user12345 -password PanelPass123", setting)
    }

    @Test
    fun `installer checksum mismatch stops before installing`() {
        installFakes("0".repeat(64))
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        writeJob(action = null)

        assertEquals(1, run())
        assertEquals("E_INSTALLER_HASH", File(jobDir, "exit").readText())
        assertFalse(xuiDir.exists())
    }

    @Test
    fun `a busy port the user chose is not silently replaced`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        listening.appendText("8443\n")
        writeJob(action = null, inboundPort = 8443, strict = true)

        assertEquals(1, run())
        assertEquals("E_PORT_BUSY:8443", File(jobDir, "exit").readText())
    }

    @Test
    fun `an existing panel without a chosen action is not touched`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        xuiDir.mkdirs()
        File(root, "fake-x-ui").copyTo(File(xuiDir, "x-ui")).setExecutable(true)
        writeJob(action = null)

        assertEquals(1, run())
        assertEquals("E_ALREADY_INSTALLED", File(jobDir, "exit").readText())
    }

    @Test
    fun `port 80 in use stops the deploy instead of falling back to an untrusted panel`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        listening.appendText("80\n")
        writeJob(action = null)

        assertEquals(1, run())
        assertEquals("E_TLS_PORT80", File(jobDir, "exit").readText())
        assertFalse(xuiDir.exists())
    }

    @Test
    fun `a failed Let's Encrypt validation fails the deploy`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        acmeFails.writeText("")
        writeJob(action = null)

        assertEquals(1, run())
        assertEquals("E_TLS", File(jobDir, "exit").readText())
        assertFalse(File(jobDir, "result.env").exists())
    }

    @Test
    fun `deploy with a domain and self-steal sets up the decoy site behind Reality`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        fakeCaddyRelease()
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        writeJob(action = null, host = "vpn.example.com", selfSteal = true)

        assertEquals(0, run())
        assertEquals("0", File(jobDir, "exit").readText())
        val result = result()
        assertEquals("8443", result["SELFSTEAL_PORT"])
        val calls = calls.readText()
        assertTrue(calls.contains("acme --issue -d vpn.example.com --standalone"))
        assertTrue(calls.contains("curl https://github.com/caddyserver/caddy/releases/download/v${DeployConfig.CADDY_VERSION}/caddy_${DeployConfig.CADDY_VERSION}_linux_amd64.tar.gz"))
        assertTrue("acme.sh must refresh the decoy site after renewals", calls.contains("--reloadcmd ${File(ssBin, "xui-manager-cert-hook").absolutePath}"))
        assertTrue(calls.contains("systemctl enable xui-caddy"))

        val caddyfile = File(ssEtc, "Caddyfile").readText()
        assertTrue(caddyfile.contains("https://vpn.example.com:8443 {"))
        assertTrue("only loopback", caddyfile.contains("bind 127.0.0.1"))
        assertTrue("port 80 must stay free for renewals", caddyfile.contains("auto_https off"))
        assertTrue(File(ssUnits, "xui-caddy.service").readText().contains("User=xui-caddy"))
        val site = File(ssWww, "index.html").readText()
        assertTrue(site.contains("Example"))
        assertTrue(File(ssBin, "xui-caddy").canExecute())
        assertTrue(File(jobDir, "log").readText().contains("==> SELFSTEAL"))
        assertFalse(File(jobDir, "caddy.tar.gz").exists())
    }

    @Test
    fun `standalone self-steal reuses the certificate and can be removed again`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        fakeCaddyRelease()
        File(certRoot, "vpn.example.com").mkdirs()
        File(certRoot, "vpn.example.com/fullchain.pem").writeText("chain")
        File(certRoot, "vpn.example.com/privkey.pem").writeText("key")
        trusted.writeText("")
        copyScripts()
        File(jobDir, "params.env").writeText(RemoteJob.selfStealEnv("install", "vpn.example.com", 0, 0))

        assertEquals(0, run("selfsteal.sh"))
        assertEquals("0", File(jobDir, "exit").readText())
        assertEquals("8443", result()["SELFSTEAL_PORT"])
        assertFalse("an existing trusted certificate is reused", calls.readText().contains("acme --issue"))
        assertEquals("chain", File(ssEtc, "fullchain.pem").readText())

        File(jobDir, "exit").delete()
        File(jobDir, "params.env").writeText(RemoteJob.selfStealEnv("remove", "vpn.example.com", 0, 0))
        assertEquals(0, run("selfsteal.sh"))
        assertEquals("0", File(jobDir, "exit").readText())
        assertFalse(ssEtc.exists())
        assertFalse(File(ssBin, "xui-caddy").exists())
        assertFalse(File(ssUnits, "xui-caddy.service").exists())
        assertTrue(calls.readText().contains("--reloadcmd systemctl restart x-ui"))
    }

    @Test
    fun `deploy with self-steal serves the user's own page`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        fakeCaddyRelease()
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        writeJob(action = null, host = "vpn.example.com", selfSteal = true)
        File(jobDir, DeployConfig.SITE_PAGE_FILE).writeText(OWN_PAGE)

        assertEquals(0, run())
        assertEquals("0", File(jobDir, "exit").readText())
        assertEquals(OWN_PAGE, File(ssWww, "index.html").readText())
        assertTrue(File(jobDir, "log").readText().contains("Custom decoy page installed"))
    }

    @Test
    fun `the page of an installed decoy site can be replaced and reset to a template`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        fakeCaddyRelease()
        File(certRoot, "vpn.example.com").mkdirs()
        File(certRoot, "vpn.example.com/fullchain.pem").writeText("chain")
        File(certRoot, "vpn.example.com/privkey.pem").writeText("key")
        trusted.writeText("")
        copyScripts()
        val page = File(jobDir, DeployConfig.SITE_PAGE_FILE).apply { writeText(OWN_PAGE) }
        File(jobDir, "params.env").writeText(RemoteJob.selfStealEnv("install", "vpn.example.com", 0, 0))
        assertEquals(0, run("selfsteal.sh"))
        assertEquals(OWN_PAGE, File(ssWww, "index.html").readText())

        File(jobDir, "exit").delete()
        page.writeText("<!doctype html><title>second</title>")
        File(jobDir, "params.env").writeText(RemoteJob.selfStealEnv("page", "vpn.example.com", 0, 0))
        val callsBefore = calls.readText()
        assertEquals(0, run("selfsteal.sh"))
        assertEquals("0", File(jobDir, "exit").readText())
        assertEquals("<!doctype html><title>second</title>", File(ssWww, "index.html").readText())
        val pageCalls = calls.readText().removePrefix(callsBefore)
        assertFalse("Caddy and the certificate stay as they are", pageCalls.contains("xui-caddy") || pageCalls.contains("acme"))

        File(jobDir, "exit").delete()
        page.delete()
        assertEquals(0, run("selfsteal.sh"))
        assertEquals("0", File(jobDir, "exit").readText())
        val site = File(ssWww, "index.html").readText()
        assertTrue("a random template is back", site.contains("Example"))
    }

    @Test
    fun `changing the page needs an installed decoy site`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        copyScripts()
        File(jobDir, DeployConfig.SITE_PAGE_FILE).writeText(OWN_PAGE)
        File(jobDir, "params.env").writeText(RemoteJob.selfStealEnv("page", "vpn.example.com", 0, 0))

        assertEquals(1, run("selfsteal.sh"))
        assertEquals("E_SELFSTEAL", File(jobDir, "exit").readText())
        assertFalse(File(ssWww, "index.html").exists())
    }

    @Test
    fun `a tampered Caddy archive is refused`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        fakeCaddyRelease()
        // Only the Caddy archive gets a wrong checksum.
        fake("sha256sum", """echo "${"0".repeat(64)}  ${'$'}1"""")
        File(certRoot, "vpn.example.com").mkdirs()
        File(certRoot, "vpn.example.com/fullchain.pem").writeText("chain")
        File(certRoot, "vpn.example.com/privkey.pem").writeText("key")
        trusted.writeText("")
        copyScripts()
        File(jobDir, "params.env").writeText(RemoteJob.selfStealEnv("install", "vpn.example.com", 0, 0))

        assertEquals(1, run("selfsteal.sh"))
        assertEquals("E_CADDY_HASH", File(jobDir, "exit").readText())
        assertFalse(File(ssBin, "xui-caddy").exists())
    }

    @Test
    fun `a long iptables rule list does not hide that ports must be opened`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(root, "fake-x-ui").writeText(fakeXui("3.10.2", withTokens = true))
        fake(
            "iptables",
            """if [ "${'$'}1" = -S ]; then
                 echo "-P INPUT ACCEPT"
                 sleep 0.3
                 for i in $(seq 1 3000); do echo "-A INPUT -s 10.${'$'}((i / 250)).${'$'}((i % 250)).1/32 -j DROP"; done
                 exit 0
               fi
               echo "iptables ${'$'}*" >> "${calls.absolutePath}"
               [ "${'$'}1" = -C ] && exit 1
               exit 0""",
        )
        writeJob(action = null)

        assertEquals(0, run())
        val calls = calls.readText()
        assertTrue("443 must be opened:\n$calls", calls.contains("iptables -I INPUT -p tcp --dport 443 -j ACCEPT"))
        assertTrue(calls.contains("iptables -I INPUT -p tcp --dport 80 -j ACCEPT"))
    }

    private fun runGeodata(action: String): Int {
        copyScripts()
        File("src/main/assets/deploy/geodata.sh").copyTo(File(jobDir, "geodata.sh"), overwrite = true)
        File(jobDir, "params.env").writeText(RemoteJob.geodataEnv(action, 0, 0))
        File(jobDir, "exit").delete()
        return run("geodata.sh")
    }

    @Test
    fun `RoscomVPN geo files are verified, installed next to Xray and refreshed by a timer`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(xuiDir, "bin").mkdirs()

        assertEquals(0, runGeodata("install"))
        assertEquals("0", File(jobDir, "exit").readText())
        assertEquals("data-geoip.dat", File(xuiDir, "bin/geoip_roscomvpn.dat").readText())
        assertEquals("data-geosite.dat", File(xuiDir, "bin/geosite_roscomvpn.dat").readText())
        assertTrue(File(ssBin, "xui-roscomvpn-geodata").canExecute())
        val timer = File(ssUnits, "xui-roscomvpn-geodata.timer").readText()
        assertTrue(timer.contains("OnCalendar=Mon"))
        assertTrue(calls.readText().contains("systemctl enable --now xui-roscomvpn-geodata.timer"))
        assertFalse("the first install must not restart the panel", calls.readText().contains("systemctl restart x-ui"))
        assertTrue(calls.readText().contains("curl https://github.com/hydraponique/roscomvpn-geoip/releases/latest/download/geoip.dat.sha256"))

        assertEquals(0, runGeodata("remove"))
        assertFalse(File(xuiDir, "bin/geoip_roscomvpn.dat").exists())
        assertFalse(File(ssUnits, "xui-roscomvpn-geodata.timer").exists())
        assertFalse(File(ssBin, "xui-roscomvpn-geodata").exists())
    }

    @Test
    fun `geo files with a wrong checksum are refused and nothing is installed`() {
        installFakes(DeployConfig.INSTALLER_SHA256)
        File(xuiDir, "bin").mkdirs()
        File(root, "geo-bad-checksum").writeText("")

        assertEquals(1, runGeodata("install"))
        assertEquals("E_GEODATA", File(jobDir, "exit").readText())
        assertFalse(File(xuiDir, "bin/geoip_roscomvpn.dat").exists())
        assertFalse(File(ssUnits, "xui-roscomvpn-geodata.timer").exists())
    }
}

private const val OWN_PAGE = "<!DOCTYPE html>\n<html><body><h1>My own page</h1></body></html>\n"
