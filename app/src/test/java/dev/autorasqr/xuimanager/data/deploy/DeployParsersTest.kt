package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.CertificateIssue
import dev.autorasqr.xuimanager.domain.model.SudoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployParsersTest {

    @Test
    fun `precheck for root with an existing panel`() {
        val result = PrecheckParser.parse(
            """
            uid=0
            gid=0
            home=/root
            arch=x86_64
            os=ubuntu
            os_version=24.04
            systemd=1
            sudo=root
            xui_installed=1
            xui_version=2.6.7
            """.trimIndent(),
        )
        assertEquals(0, result.uid)
        assertEquals(SudoMode.ROOT, result.sudo)
        assertEquals("/root", result.home)
        assertTrue(result.systemd)
        assertTrue(result.existingInstalled)
        assertEquals("2.6.7", result.existingVersion)
    }

    @Test
    fun `precheck for sudo user without panel`() {
        val result = PrecheckParser.parse("uid=1000\ngid=1001\nhome=/home/deploy\nsudo=password\nsystemd=1\nxui_installed=0\nxui_version=\n")
        assertEquals(SudoMode.PASSWORD, result.sudo)
        assertEquals(1001, result.gid)
        assertFalse(result.existingInstalled)
        assertNull(result.existingVersion)
    }

    @Test
    fun `precheck with a domain lists resolved and own addresses`() {
        val result = PrecheckParser.parse(
            "uid=0\nsudo=root\nsystemd=1\nxui_installed=0\ndomain_ips=203.0.113.7 \nserver_ips=10.0.0.5 172.17.0.1  203.0.113.7\n",
        )
        assertEquals(listOf("203.0.113.7"), result.domainIps)
        assertEquals(listOf("10.0.0.5", "172.17.0.1", "203.0.113.7"), result.serverIps)
    }

    @Test
    fun `precheck without a domain has no dns result`() {
        val result = PrecheckParser.parse("uid=0\nsudo=root\nsystemd=1\nxui_installed=0\n")
        assertEquals(null, result.domainIps)
        assertEquals(emptyList<String>(), result.serverIps)
    }

    @Test(expected = AppException::class)
    fun `precheck without uid fails`() {
        PrecheckParser.parse("bash: line 1: id: command not found\n")
    }

    @Test
    fun `result env is parsed and fingerprint normalized`() {
        val result = ResultEnvParser.parse(
            """
            PANEL_PORT=34567
            API_TOKEN=tok_abc123
            SUB_PORT=45678
            SUB_CERT=/root/cert/ip/fullchain.pem
            SUB_KEY=/root/cert/ip/privkey.pem
            INBOUND_PORT=443
            PANEL_VERSION=3.10.2
            EXISTED=0
            """.trimIndent(),
        )
        assertEquals(34567, result.panelPort)
        assertEquals("tok_abc123", result.apiToken)
        assertEquals("/root/cert/ip/fullchain.pem", result.subCertFile)
        assertEquals(443, result.inboundPort)
        assertFalse(result.existed)
    }

    @Test
    fun `result env without subscription certificate`() {
        val result = ResultEnvParser.parse(
            "PANEL_PORT=1\nAPI_TOKEN=t\nSUB_PORT=2\nSUB_CERT=\nSUB_KEY=\nINBOUND_PORT=3\nPANEL_VERSION=\nEXISTED=1\n",
        )
        assertNull(result.subCertFile)
        assertNull(result.subKeyFile)
        assertEquals(DeployConfig.XUI_VERSION, result.panelVersion)
        assertTrue(result.existed)
    }

    @Test
    fun `adopted 2_x panel reports its own path and no token`() {
        val result = ResultEnvParser.parse(
            "PANEL_PORT=2053\nAPI_TOKEN=\nSUB_PORT=0\nSUB_CERT=\nSUB_KEY=\nINBOUND_PORT=0\n" +
                "PANEL_VERSION=2.6.7\nEXISTED=1\nWEB_BASE_PATH=/old/\n",
        )
        assertNull(result.apiToken)
        assertEquals("/old/", result.webBasePath)
        assertEquals(0, result.inboundPort)
        assertEquals("2.6.7", result.panelVersion)
    }

    @Test
    fun `result env without a panel port is rejected`() {
        val error = runCatching { ResultEnvParser.parse("API_TOKEN=t\n") }.exceptionOrNull() as AppException
        assertTrue(error.error is AppError.Unexpected)
    }

    @Test
    fun `x25519 legacy output`() {
        val keys = X25519Parser.parse(
            "Private key: WFp1u_7Hgn8YWkRCMZ0X6hAEUJh-yDu3o5eAG4MOLWo\nPublic key: Xo3s7ZGOX8HWB0bHtz1bplE0g0ECpzRXJ5o5Qp5b0Fs\n",
        )
        assertEquals("WFp1u_7Hgn8YWkRCMZ0X6hAEUJh-yDu3o5eAG4MOLWo", keys?.privateKey)
        assertEquals("Xo3s7ZGOX8HWB0bHtz1bplE0g0ECpzRXJ5o5Qp5b0Fs", keys?.publicKey)
    }

    @Test
    fun `x25519 current output with password and hash`() {
        val keys = X25519Parser.parse(
            """
            PrivateKey: WFp1u_7Hgn8YWkRCMZ0X6hAEUJh-yDu3o5eAG4MOLWo
            Password (PublicKey): Xo3s7ZGOX8HWB0bHtz1bplE0g0ECpzRXJ5o5Qp5b0Fs
            Hash32: 8vT1q0r4A2Y9vWb3k6m7n8p0q1r2s3t4u5v6w7x8y9z
            """.trimIndent(),
        )
        assertEquals("WFp1u_7Hgn8YWkRCMZ0X6hAEUJh-yDu3o5eAG4MOLWo", keys?.privateKey)
        assertEquals("Xo3s7ZGOX8HWB0bHtz1bplE0g0ECpzRXJ5o5Qp5b0Fs", keys?.publicKey)
    }

    @Test
    fun `x25519 garbage yields null`() {
        assertNull(X25519Parser.parse("bash: xray: command not found"))
        assertNull(X25519Parser.parse("PrivateKey: short\nPassword: alsoShort"))
    }

    @Test
    fun `poll output with log chunk`() {
        val snapshot = PollParser.parse("INSTALL\n\n1\nline one\nline two\npart".toByteArray())
        assertEquals("INSTALL", snapshot.stage)
        assertNull(snapshot.exitCode)
        assertEquals(Liveness.ALIVE, snapshot.liveness)
        assertEquals("line one\nline two\npart", snapshot.logChunk.decodeToString())
    }

    @Test
    fun `poll output finished`() {
        val snapshot = PollParser.parse("DONE\n0\n0\n".toByteArray())
        assertEquals("0", snapshot.exitCode)
        assertEquals(Liveness.DEAD, snapshot.liveness)
        assertEquals(0, snapshot.logChunk.size)
    }

    @Test
    fun `poll output without job`() {
        val snapshot = PollParser.parse("NOJOB\n\n0\n".toByteArray())
        assertTrue(snapshot.noJob)
        assertNull(snapshot.stage)
    }

    @Test
    fun `poll output empty`() {
        val snapshot = PollParser.parse(ByteArray(0))
        assertNull(snapshot.stage)
        assertEquals(Liveness.NOT_STARTED, snapshot.liveness)
    }

    @Test
    fun `log assembler keeps partial lines and multibyte characters`() {
        val assembler = LogLineAssembler()
        val text = "Установка\nготово\n".toByteArray()
        val split = 5 // inside the second Cyrillic letter
        assertEquals(emptyList<String>(), assembler.feed(text.copyOfRange(0, split)))
        assertEquals(listOf("Установка", "готово"), assembler.feed(text.copyOfRange(split, text.size)))
        assertNull(assembler.flush())
        assertEquals(emptyList<String>(), assembler.feed("tail".toByteArray()))
        assertEquals("tail", assembler.flush())
    }

    @Test
    fun `exit codes map to errors`() {
        assertEquals(AppError.PortBusy(443), DeployExitCodes.toAppError("E_PORT_BUSY:443"))
        assertEquals(AppError.InstallFailed("2"), DeployExitCodes.toAppError("E_INSTALL:2"))
        assertEquals(AppError.InstallerHashMismatch, DeployExitCodes.toAppError("E_INSTALLER_HASH"))
        assertEquals(AppError.CertificateIssueFailed(CertificateIssue.PORT_80_BUSY), DeployExitCodes.toAppError("E_TLS_PORT80"))
        assertEquals(AppError.CertificateIssueFailed(CertificateIssue.VALIDATION_FAILED), DeployExitCodes.toAppError("E_TLS"))
        assertEquals(AppError.Unexpected("E_UNEXPECTED:1"), DeployExitCodes.toAppError("E_UNEXPECTED:1"))
    }
}
