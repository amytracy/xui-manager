package dev.autorasqr.xuimanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.autorasqr.xuimanager.data.ssh.CryptoProviders
import dev.autorasqr.xuimanager.data.ssh.HostKeys
import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.util.Base64

/** Things that depend on the device's crypto stack rather than the JVM's. */
@RunWith(AndroidJUnit4::class)
class CryptoDeviceTest {

    @Test
    fun ed25519HostKeyFingerprintOnArt() {
        CryptoProviders.install()
        val blob = Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAIBSdQuk46FmODzcw7Kss8AKvUlA7fvArjRRUAhi9k3L5")
        val key = Buffer.PlainBuffer(blob).readPublicKey()
        assertEquals(HostKeyPin("ssh-ed25519", "SHA256:GkargYAEY03otq9yPwGHyw/iULp8VN0AILdVYoaWvi4"), HostKeys.pinOf(key))
    }

    @Test
    fun sshjLoadsAPkcs8KeyOnArt() {
        CryptoProviders.install()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val provider = SSHClient().loadKeys(pem, null, null)
        assertEquals(pair.public, provider.public)
    }
}
