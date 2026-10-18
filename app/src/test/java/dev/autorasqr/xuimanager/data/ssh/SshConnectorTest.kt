package dev.autorasqr.xuimanager.data.ssh

import dev.autorasqr.xuimanager.data.deploy.PrecheckParser
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import dev.autorasqr.xuimanager.domain.model.SshCredentials
import dev.autorasqr.xuimanager.domain.model.SudoMode
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/** Runs the sshj-based connector against an in-process SSH server whose commands go through /bin/sh -c, like OpenSSH. */
class SshConnectorTest {

    private val workDir = Files.createTempDirectory("xui-sshd").toFile()
    private val clientKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val sshd = SshServer.setUpDefaultServer()
    private val connector = SshConnector()
    private lateinit var hostPin: HostKeyPin

    @Before
    fun setUp() {
        sshd.host = "127.0.0.1"
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File(workDir, "hostkey.ser").toPath())
        sshd.passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
        sshd.publickeyAuthenticator = PublickeyAuthenticator { user, key, _ -> user == USER && key == clientKey.public }
        sshd.commandFactory = CommandFactory { channel, command ->
            ProcessShellFactory(command, "/bin/sh", "-c", command).createShell(channel)
        }
        sshd.start()
        hostPin = HostKeys.pinOf(sshd.keyPairProvider.loadKeys(null).first().public)
    }

    @After
    fun tearDown() {
        sshd.stop(true)
        workDir.deleteRecursively()
    }

    private fun target(
        credentials: SshCredentials = SshCredentials.Password(PASSWORD),
        pin: HostKeyPin? = hostPin,
    ) = SshTarget("127.0.0.1", sshd.port, USER, credentials, pin)

    @Test
    fun `host key is fetched without authenticating`() = runBlocking {
        assertEquals(hostPin, connector.fetchHostKey("127.0.0.1", sshd.port))
    }

    @Test
    fun `unknown and changed host keys are refused`() = runBlocking {
        val unknown = runCatching { connector.withSession(target(pin = null)) { } }.exceptionOrNull() as AppException
        assertEquals(AppError.HostKeyUnknown(hostPin), unknown.error)

        val wrong = HostKeyPin(hostPin.algorithm, "SHA256:" + "A".repeat(43))
        val changed = runCatching { connector.withSession(target(pin = wrong)) { } }.exceptionOrNull() as AppException
        assertEquals(AppError.HostKeyMismatch(wrong, hostPin), changed.error)
    }

    @Test
    fun `wrong password is reported as auth failure`() = runBlocking {
        val error = runCatching { connector.withSession(target(SshCredentials.Password("nope"))) { } }.exceptionOrNull() as AppException
        assertEquals(AppError.SshAuthFailed, error.error)
    }

    @Test
    fun `private key login with a PKCS8 PEM`() = runBlocking {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(clientKey.private.encoded) +
            "\n-----END PRIVATE KEY-----"
        val out = connector.withSession(target(SshCredentials.PrivateKey(pem, null))) { it.execSh("echo key-ok").stdoutText }
        assertEquals("key-ok\n", out)
    }

    @Test
    fun `garbage private key is rejected before auth`() = runBlocking {
        val error = runCatching {
            connector.withSession(target(SshCredentials.PrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----", null))) { }
        }.exceptionOrNull() as AppException
        assertEquals(AppError.InvalidPrivateKey, error.error)
    }

    @Test
    fun `exec passes stdin, exit code and stderr`() = runBlocking {
        connector.withSession(target()) { session ->
            // Large stdin with small output exercises the concurrent writer. Echoing large payloads back is kept
            // under ~128 KB because MINA's process wrapper (unlike OpenSSH) pumps stdin and stdout on one thread.
            val large = ByteArray(300_000) { (it % 251).toByte() }
            assertEquals("300000", session.execSh("wc -c", stdin = large).stdoutText.trim())
            val payload = ByteArray(60_000) { (it % 251).toByte() }
            assertArrayEquals(payload, session.execSh("cat", stdin = payload).stdout)
            val failed = session.execSh("echo oops >&2; exit 3")
            assertEquals(3, failed.exitCode)
            assertEquals("oops\n", failed.stderr)
            val root = session.execPrivileged("echo \"it's \$((1+1))\"", SudoMode.ROOT)
            assertEquals("it's 2\n", root.stdoutText)
        }
    }

    @Test
    fun `private files are created with mode 600`() = runBlocking {
        val path = File(workDir, "jobs/1/secrets.env").absolutePath
        connector.withSession(target()) { it.writePrivateFile(path, "A='b'\n".toByteArray()) }
        assertEquals("A='b'\n", File(path).readText())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(File(path).toPath()),
        )
    }

    @Test
    fun `precheck script runs through sh -s`() = runBlocking {
        val script = File("src/main/assets/deploy/precheck.sh").readBytes()
        val result = connector.withSession(target()) { PrecheckParser.parse(it.exec("sh -s", stdin = script).stdoutText) }
        assertEquals(currentUid(), result.uid)
        assertTrue(result.home.isNotEmpty())
        assertTrue(result.arch.isNotEmpty())
    }

    @Test
    fun `hung commands time out`() = runBlocking {
        val started = System.currentTimeMillis()
        try {
            connector.withSession(target()) { it.execSh("sleep 30", timeoutSeconds = 1) }
            fail("expected timeout")
        } catch (e: AppException) {
            assertEquals(AppError.Timeout, e.error)
        }
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }

    private fun currentUid(): Int =
        ProcessBuilder("id", "-u").start().inputStream.readBytes().decodeToString().trim().toInt()

    private companion object {
        const val USER = "tester"
        const val PASSWORD = "s3cret"
    }
}
