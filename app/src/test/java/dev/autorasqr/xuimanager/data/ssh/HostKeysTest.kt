package dev.autorasqr.xuimanager.data.ssh

import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HostKeysTest {

    // Generated with ssh-keygen -t ed25519; expected value from `ssh-keygen -lf`.
    private val ed25519Blob = "AAAAC3NzaC1lZDI1NTE5AAAAIBSdQuk46FmODzcw7Kss8AKvUlA7fvArjRRUAhi9k3L5"
    private val ed25519Pin = HostKeyPin("ssh-ed25519", "SHA256:GkargYAEY03otq9yPwGHyw/iULp8VN0AILdVYoaWvi4")

    private fun key() = Buffer.PlainBuffer(Base64.getDecoder().decode(ed25519Blob)).readPublicKey()

    @Test
    fun `fingerprint matches ssh-keygen`() {
        assertEquals(ed25519Pin, HostKeys.pinOf(key()))
    }

    @Test
    fun `verifier without a pin rejects and reports the key`() {
        val verifier = PinningHostKeyVerifier(expected = null)
        assertFalse(verifier.verify("h", 22, key()))
        assertTrue(verifier.rejected)
        assertEquals(ed25519Pin, verifier.presented)
        assertEquals(emptyList<String>(), verifier.findExistingAlgorithms("h", 22))
    }

    @Test
    fun `verifier accepts the pinned key and prefers its algorithm`() {
        val verifier = PinningHostKeyVerifier(ed25519Pin)
        assertTrue(verifier.verify("h", 22, key()))
        assertFalse(verifier.rejected)
        assertEquals(listOf("ssh-ed25519"), verifier.findExistingAlgorithms("h", 22))
    }

    @Test
    fun `verifier rejects a different key`() {
        val verifier = PinningHostKeyVerifier(HostKeyPin("ssh-ed25519", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(verifier.verify("h", 22, key()))
        assertTrue(verifier.rejected)
    }

    @Test
    fun `shell quoting survives single quotes`() {
        assertEquals("'it'\\''s'", shQuote("it's"))
        val process = ProcessBuilder("/bin/sh", "-c", "printf %s ${shQuote("a'b \$HOME \"c\"")}").start()
        assertEquals("a'b \$HOME \"c\"", process.inputStream.readBytes().decodeToString())
    }
}
