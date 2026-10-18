package dev.autorasqr.xuimanager.data.deploy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the real precheck.sh with `sh -s`, the way the app does, against fake getent, ip and curl. */
class PrecheckScriptTest {

    private val root: File = Files.createTempDirectory("xui-precheck").toFile()
    private val bin = File(root, "bin").apply { mkdirs() }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fake(name: String, body: String) {
        File(bin, name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }
    }

    private fun run(prefix: String): Map<String, String> {
        val script = prefix.toByteArray() + File("src/main/assets/deploy/precheck.sh").readBytes()
        val process = ProcessBuilder("sh", "-s")
            .apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["XUI_MANAGER_XUI_DIR"] = File(root, "x-ui").absolutePath
            }
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write(script) }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return parseKeyValues(output)
    }

    private fun fakeNetwork() {
        fake(
            "getent",
            """[ "${'$'}1" = ahostsv4 ] && [ "${'$'}2" = vpn.example.com ] || exit 2
               echo "203.0.113.7     STREAM vpn.example.com"
               echo "203.0.113.7     DGRAM"
               echo "203.0.113.7     RAW"""",
        )
        fake("ip", """echo "2: eth0    inet 10.0.0.5/24 brd 10.0.0.255 scope global eth0\       valid_lft forever preferred_lft forever"""")
        fake("curl", "echo 203.0.113.7")
    }

    @Test
    fun `domain is resolved and the server addresses are listed`() {
        fakeNetwork()
        val values = run("DOMAIN='vpn.example.com'\n")
        assertEquals("203.0.113.7", values["domain_ips"])
        assertEquals(listOf("10.0.0.5", "203.0.113.7"), values["server_ips"]!!.split(' ').filter { it.isNotBlank() })
        assertEquals("0", values["xui_installed"])
    }

    @Test
    fun `no network lookups without a domain`() {
        fakeNetwork()
        val values = run("")
        assertEquals(null, values["domain_ips"])
        assertEquals(null, values["server_ips"])
    }

    @Test
    fun `unresolvable domain reports no addresses`() {
        fakeNetwork()
        val values = run("DOMAIN='missing.example.com'\n")
        assertEquals("", values["domain_ips"])
        assertEquals(PrecheckParser.parse("uid=0\ndomain_ips=\n").domainIps, emptyList<String>())
    }
}
