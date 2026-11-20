package dev.autorasqr.xuimanager.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class TransferCodecTest {
    private val random = SecureRandom()

    private fun server(ref: Long, masterRef: Long? = null, key: String? = null) = TransferServer(
        ref = ref,
        name = "srv$ref",
        host = "203.0.113.$ref",
        sshPort = 22,
        sshUser = "root",
        authType = if (key != null) "PRIVATE_KEY" else "PASSWORD",
        hostKeyAlgorithm = "ssh-ed25519",
        hostKeyFingerprint = "SHA256:abc$ref",
        masterRef = masterRef,
        nodeId = masterRef?.let { ref.toInt() },
        panel = TransferPanel("v3.10.2", 34567, "/base/", "admin", "https", 2096, "/sub/", 1, 5, "vpn.example.com", 8443),
        sshPassword = if (key == null) "pa:ss&word=$ref" else null,
        sshPrivateKey = key,
        panelPassword = "panel$ref",
        panelToken = "token$ref",
    )

    private val bundle = TransferBundle(createdAt = 42, servers = listOf(server(1), server(2, masterRef = 1)), uiStyle = "ONE_UI")

    private fun expectReason(reason: TransferException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: TransferException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun qrCodesInAnyOrderGiveTheBundleBack() {
        val big = bundle.copy(servers = (1L..6L).map { server(it, masterRef = if (it == 1L) null else 1L, key = randomKey()) })
        val codes = TransferCodec.qrCodes(TransferCodec.seal(big, random), random)
        assertTrue("several codes for a big bundle", codes.size > 1)
        assertTrue(codes.all { it.length < 700 })
        val collector = TransferCodec.QrCollector()
        val shuffled = codes.shuffled()
        shuffled.forEach { collector.add(it) }
        assertFalse("a repeated code is not new", collector.add(shuffled.first()))
        assertTrue(collector.isComplete)
        assertEquals(big, collector.open())
    }

    @Test
    fun linkWithItsCodeGivesTheBundleBack() {
        val sealed = TransferCodec.seal(bundle, random)
        val code = TransferCodec.newCode(random)
        val link = TransferCodec.link(sealed, code, random)
        assertTrue(link.startsWith(TransferCodec.LINK_PREFIX))
        // Chat apps wrap long text and people type codes in lower case without the dash.
        val wrapped = "Here: " + link.chunked(80).joinToString("\n")
        assertEquals(bundle, TransferCodec.openLink(wrapped, code.lowercase().replace("-", " ")))
    }

    @Test
    fun wrongCodeIsRejected() {
        val sealed = TransferCodec.seal(bundle, random)
        val link = TransferCodec.link(sealed, "AAAAA-BBBBB", random)
        expectReason(TransferException.Reason.WRONG_CODE) { TransferCodec.openLink(link, "AAAAA-BBBBC") }
    }

    @Test
    fun alteredLinkIsDamaged() {
        val sealed = TransferCodec.seal(bundle, random)
        val link = TransferCodec.link(sealed, "AAAAA-BBBBB", random)
        val d = link.substringAfter("&d=")
        val bytes = Base64.getUrlDecoder().decode(d)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        val altered = link.substringBefore("&d=") + "&d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        expectReason(TransferException.Reason.DAMAGED) { TransferCodec.openLink(altered, "AAAAA-BBBBB") }
        expectReason(TransferException.Reason.DAMAGED) { TransferCodec.openLink(link.dropLast(10), "AAAAA-BBBBB") }
    }

    @Test
    fun codesOfAnotherTransferAreNotMixedIn() {
        val first = TransferCodec.qrCodes(TransferCodec.seal(bundle, random), random)
        val second = TransferCodec.qrCodes(TransferCodec.seal(bundle, random), random)
        val collector = TransferCodec.QrCollector()
        collector.add(first.first())
        expectReason(TransferException.Reason.OTHER_TRANSFER) { collector.add(second.first()) }
    }

    @Test
    fun foreignTextIsNotATransfer() {
        val collector = TransferCodec.QrCollector()
        expectReason(TransferException.Reason.NOT_A_TRANSFER) { collector.add("vless://uuid@host:443") }
        expectReason(TransferException.Reason.NOT_A_TRANSFER) { collector.add("xuim1:ABC:3:2:key:part") }
        expectReason(TransferException.Reason.NOT_A_TRANSFER) { TransferCodec.openLink("https://example.com", "x") }
        assertFalse(TransferCodec.isLink("https://example.com"))
    }

    @Test
    fun newerBundleAsksForAnUpdate() {
        val sealed = TransferCodec.seal(bundle.copy(v = TransferBundle.VERSION + 1), random)
        expectReason(TransferException.Reason.NEWER_VERSION) { TransferCodec.open(sealed.key, sealed.cipher) }
    }

    @Test
    fun codesUseTheUnambiguousAlphabet() {
        repeat(50) {
            val code = TransferCodec.newCode(random)
            assertTrue(code, Regex("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}").matches(code))
        }
        assertEquals("0111ABC", TransferCodec.normalizeCode("o-i l1 abc"))
    }

    @Test
    fun secretsAreNotInToString() {
        assertFalse(server(1).toString().contains("panel1"))
        assertFalse(server(1).toString().contains("token1"))
    }

    private fun randomKey(): String {
        val body = ByteArray(2400).also(random::nextBytes)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            Base64.getMimeEncoder().encodeToString(body) + "\n-----END OPENSSH PRIVATE KEY-----"
    }
}
