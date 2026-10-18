package dev.autorasqr.xuimanager.data.ssh

import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

object HostKeys {
    /** OpenSSH-style fingerprint: "SHA256:" + unpadded base64 of SHA-256 over the public key blob. */
    fun pinOf(key: PublicKey): HostKeyPin {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return HostKeyPin(algorithm = KeyType.fromKey(key).toString(), fingerprint = "SHA256:$encoded")
    }

    fun matches(expected: HostKeyPin, presented: HostKeyPin): Boolean =
        MessageDigest.isEqual(expected.fingerprint.toByteArray(), presented.fingerprint.toByteArray())
}

/**
 * Accepts only the pinned key. With no pin it rejects everything, so an unknown key never gets through
 * silently; the caller reads [presented] to ask the user.
 */
internal class PinningHostKeyVerifier(private val expected: HostKeyPin?) : HostKeyVerifier {
    @Volatile var presented: HostKeyPin? = null
        private set

    @Volatile var rejected: Boolean = false
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val pin = HostKeys.pinOf(key)
        presented = pin
        val accepted = expected != null && HostKeys.matches(expected, pin)
        rejected = !accepted
        return accepted
    }

    /** Prefer the pinned key type during negotiation so a server with several host keys stays verifiable. */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        expected?.let { listOf(it.algorithm) } ?: emptyList()
}
