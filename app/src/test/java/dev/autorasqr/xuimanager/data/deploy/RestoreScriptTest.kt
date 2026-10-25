package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.AppError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the real restore.sh with bash against a fake x-ui, openssl and systemctl placed first on PATH. It checks what
 * the app reads back, that only the login and the token change, and that no secret reaches the log.
 */
class RestoreScriptTest {

    private val root: File = Files.createTempDirectory("xui-restore").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }
    private val calls = File(root, "calls").apply { writeText("") }
    private val xuiDir = File(root, "x-ui")
    private val ssEtc = File(root, "ss-etc")
    private val jobDir = File(root, "job").apply { mkdirs() }
    private val cert = File(root, "cert/fullchain.pem")

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

    /**
     * [sans] is the certificate's subjectAltName line, [trustedFor] the names it verifies for (none: not trusted).
     * [withTokens] selects 3.x behaviour (API tokens) or 2.x.
     */
    private fun install(
        sans: String = "DNS:vpn.example.com",
        trustedFor: List<String> = listOf("vpn.example.com"),
        withTokens: Boolean = true,
        version: String = "v3.10.2",
        selfStealPort: Int? = null,
    ) {
        cert.parentFile?.mkdirs()
        cert.writeText("chain")
        File(cert.parentFile, "privkey.pem").writeText("key")
        File(xuiDir, "x-ui").apply {
            parentFile?.mkdirs()
            writeText(
                """#!/bin/bash
                echo "x-ui ${'$'}*" >> "${calls.absolutePath}"
                case "${'$'}1" in
                  -v) echo "$version" ;;
                  setting)
                    case "${'$'}2" in
                      -show) printf 'The panel is using SSL\nhasDefaultCredential: false\nport: 2053\nwebBasePath: /old/\n' ;;
                      -getCert) echo "cert: ${cert.absolutePath}"; echo "key: ${cert.parentFile!!.absolutePath}/privkey.pem" ;;
                      -h) echo "Usage of setting:" >&2; ${if (withTokens) "echo '  -getApiToken  Print an API token' >&2" else ":"} ;;
                      -getApiToken) echo "apiToken: tok-secret-123" ;;
                      *) echo "Username: ${'$'}3 Password: ${'$'}5"; echo "set username and password success" ;;
                    esac ;;
                esac
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val trusted = trustedFor.joinToString(" ")
        fake("id", """case "${'$'}1" in -u|-g) echo 0 ;; *) echo "uid=0(root)" ;; esac""")
        fake("chown", "exit 0")
        fake("systemctl", "exit 0")
        fake(
            "openssl",
            """case "${'$'}*" in
                 *-text*) printf 'Certificate:\n        X509v3 extensions:\n            X509v3 Subject Alternative Name: \n                $sans\n' ;;
                 *-checkend*) exit 0 ;;
                 *-checkhost* | *-checkip*)
                   for n in $trusted; do [ "${'$'}n" = "${'$'}{@: -1}" ] && { echo "${'$'}n does match certificate"; exit 0; }; done
                   echo "does NOT match certificate" ;;
                 verify*) ${if (trustedFor.isEmpty()) "exit 2" else "exit 0"} ;;
               esac""",
        )
        if (selfStealPort != null) {
            ssEtc.mkdirs()
            File(ssEtc, "Caddyfile").writeText("{\n\tadmin off\n}\n\nhttps://vpn.example.com:$selfStealPort {\n\tbind 127.0.0.1\n}\n")
        }
    }

    private fun run(preferredHost: String = "203.0.113.7"): Int {
        listOf("lib.sh", "restore.sh").forEach { File("src/main/assets/deploy/$it").copyTo(File(jobDir, it), overwrite = true) }
        File(jobDir, "params.env").writeText(RemoteJob.restoreEnv(0, 0, preferredHost))
        File(jobDir, "secrets.env").writeText(RemoteJob.restoreSecretsEnv("newuser42", "NewPanelPass123"))
        listOf("log", "stage").forEach { File(jobDir, it).writeText("") }
        val process = ProcessBuilder("bash", File(jobDir, "restore.sh").absolutePath)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["XUI_MANAGER_XUI_DIR"] = xuiDir.absolutePath
                environment()["XUI_MANAGER_SS_ETC"] = ssEtc.absolutePath
            }
            .start()
        process.inputStream.readBytes()
        assertTrue("restore.sh hung", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun exitCode() = File(jobDir, "exit").readText()
    private fun log() = File(jobDir, "log").readText()
    private fun calls() = calls.readLines()
    private fun result(): Map<String, String> = parseKeyValues(File(jobDir, "result.env").readText())

    @Test
    fun `finds the panel, its domain and the decoy site and only replaces the login and the token`() {
        install(selfStealPort = 8443)
        run()
        assertEquals(log(), "0", exitCode())
        val result = result()
        assertEquals("2053", result["PANEL_PORT"])
        assertEquals("/old/", result["WEB_BASE_PATH"])
        assertEquals("v3.10.2", result["PANEL_VERSION"])
        assertEquals("tok-secret-123", result["API_TOKEN"])
        assertEquals("vpn.example.com", result["CERT_HOST"])
        assertEquals("8443", result["SELFSTEAL_PORT"])

        assertTrue(calls().contains("x-ui setting -username newuser42 -password NewPanelPass123"))
        assertTrue(calls().contains("x-ui setting -getApiToken -tokenName xui-manager"))
        // Nothing is installed, restarted or opened.
        assertFalse(calls().toString(), calls().any { it.startsWith("systemctl") || it.startsWith("x-ui cert") || it.contains("-port") })

        assertFalse(File(jobDir, "secrets.env").exists())
        listOf("NewPanelPass123", "newuser42", "tok-secret-123").forEach { assertFalse("$it leaked", log().contains(it)) }
        assertTrue(log().contains("Nothing was reinstalled"))
    }

    @Test
    fun `a panel with an IP certificate is reached through the address`() {
        install(sans = "IP Address:203.0.113.7", trustedFor = listOf("203.0.113.7"))
        run(preferredHost = "203.0.113.7")
        assertEquals(log(), "0", exitCode())
        assertEquals("203.0.113.7", result()["CERT_HOST"])
        assertEquals("", result()["SELFSTEAL_PORT"])
        assertTrue(log().contains("No decoy site"))
    }

    @Test
    fun `falls back to the name the certificate covers when the ssh host is not one of them`() {
        install(sans = "DNS:vpn.example.com, DNS:*.example.com, IP Address:198.51.100.1", trustedFor = listOf("vpn.example.com"))
        run(preferredHost = "203.0.113.7")
        assertEquals(log(), "0", exitCode())
        assertEquals("vpn.example.com", result()["CERT_HOST"])
    }

    @Test
    fun `fails when 3x-ui is not installed`() {
        install()
        File(xuiDir, "x-ui").delete()
        run()
        assertEquals("E_RESTORE_MISSING", exitCode())
        assertEquals(AppError.RestoreNoPanel, DeployExitCodes.toAppError(exitCode()))
        assertFalse(File(jobDir, "result.env").exists())
    }

    @Test
    fun `fails without touching the panel when its certificate is not trusted`() {
        install(trustedFor = emptyList())
        run()
        assertEquals("E_RESTORE_CERT", exitCode())
        assertEquals(AppError.RestoreNoTrustedCert, DeployExitCodes.toAppError(exitCode()))
        assertFalse(calls().any { it.startsWith("x-ui setting -username") || it.contains("-getApiToken") })
    }

    @Test
    fun `a 2_x panel has no token and is reached with the new login`() {
        install(withTokens = false, version = "v2.8.4")
        run()
        assertEquals(log(), "0", exitCode())
        assertEquals("", result()["API_TOKEN"])
        assertTrue(calls().contains("x-ui setting -username newuser42 -password NewPanelPass123"))
    }
}
