package dev.autorasqr.xuimanager.data.transfer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.SCrypt
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TransferException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Not a transfer link or QR code of this app. */
        NOT_A_TRANSFER,
        /** The link's code does not match. */
        WRONG_CODE,
        /** Cut short or altered on the way. */
        DAMAGED,
        /** Made by a newer app version. */
        NEWER_VERSION,
        /** A QR code of another transfer was scanned in between. */
        OTHER_TRANSFER,
    }
}

/** The bundle sealed with a random key: [cipher] is the same for the QR codes and the link. */
class SealedBundle(val key: ByteArray, val cipher: ByteArray)

/**
 * Transfer format.
 *
 * The bundle is JSON, deflated and sealed with AES-256-GCM under a random key K.
 * - QR codes carry K in the clear (they are scanned face to face): `xuim1:<session>:<i>:<n>:<K>:<part of the cipher>`,
 *   scanned in any order.
 * - The link carries K wrapped with a key derived from a separate code (scrypt), so a link leaked from a chat is
 *   useless without the code: `xuimanager://import?v=1&s=<salt>&w=<wrapped K>&d=<cipher>`.
 */
object TransferCodec {
    const val LINK_PREFIX = "xuimanager://import?"
    private const val QR_PREFIX = "xuim1"
    private const val QR_PART_CHARS = 500
    private const val CODE_LENGTH = 10
    private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val NONCE_BYTES = 12
    private const val MAX_PLAIN_BYTES = 1 shl 20
    private val AAD = "xuim1".toByteArray()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    fun seal(bundle: TransferBundle, random: SecureRandom = SecureRandom()): SealedBundle {
        val key = ByteArray(32).also(random::nextBytes)
        val plain = deflate(json.encodeToString(TransferBundle.serializer(), bundle).toByteArray())
        return SealedBundle(key, encrypt(key, plain, random))
    }

    fun open(key: ByteArray, cipher: ByteArray): TransferBundle {
        val plain = try {
            inflate(decrypt(key, cipher))
        } catch (_: GeneralSecurityException) {
            throw TransferException(TransferException.Reason.DAMAGED)
        }
        val version = try {
            json.decodeFromString(VersionOnly.serializer(), plain.decodeToString()).v
        } catch (_: SerializationException) {
            throw TransferException(TransferException.Reason.DAMAGED)
        }
        if (version > TransferBundle.VERSION) throw TransferException(TransferException.Reason.NEWER_VERSION)
        return try {
            json.decodeFromString(TransferBundle.serializer(), plain.decodeToString())
        } catch (_: SerializationException) {
            throw TransferException(TransferException.Reason.DAMAGED)
        } catch (_: IllegalArgumentException) {
            throw TransferException(TransferException.Reason.DAMAGED)
        }
    }

    // --- QR codes ---

    fun qrCodes(sealed: SealedBundle, random: SecureRandom = SecureRandom()): List<String> {
        val session = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val key = b64.encodeToString(sealed.key)
        val parts = b64.encodeToString(sealed.cipher).chunked(QR_PART_CHARS)
        return parts.mapIndexed { index, part -> "$QR_PREFIX:$session:${index + 1}:${parts.size}:$key:$part" }
    }

    /** Collects scanned QR codes of one transfer in any order. Not thread-safe. */
    class QrCollector {
        private var session: String? = null
        private var key: String? = null
        private var total = 0
        private val parts = sortedMapOf<Int, String>()

        val received: Int get() = parts.size
        val expected: Int get() = total
        val isComplete: Boolean get() = total > 0 && parts.size == total

        /** Returns true when [text] was a new part; throws for text that is not a part of this transfer. */
        fun add(text: String): Boolean {
            val fields = text.trim().split(':')
            if (fields.size != 6 || fields[0] != QR_PREFIX) throw TransferException(TransferException.Reason.NOT_A_TRANSFER)
            val (_, session, indexText, totalText, key, part) = fields
            val index = indexText.toIntOrNull()
            val count = totalText.toIntOrNull()
            if (index == null || count == null || count !in 1..999 || index !in 1..count || part.isEmpty()) {
                throw TransferException(TransferException.Reason.NOT_A_TRANSFER)
            }
            if (this.session == null) {
                this.session = session
                this.key = key
                total = count
            } else if (this.session != session || this.key != key || total != count) {
                throw TransferException(TransferException.Reason.OTHER_TRANSFER)
            }
            return parts.put(index, part) == null
        }

        fun open(): TransferBundle {
            check(isComplete) { "missing parts" }
            val cipher = decode(parts.values.joinToString(""))
            return open(decode(key!!), cipher)
        }

        private operator fun <T> List<T>.component6() = this[5]
    }

    // --- Link ---

    /** A new code for the link, e.g. "7KQ2M-XW4PA". */
    fun newCode(random: SecureRandom = SecureRandom()): String {
        val chars = (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        return chars.chunked(CODE_LENGTH / 2).joinToString("-")
    }

    /** Upper case, no separators, look-alike letters read as digits (Crockford). */
    fun normalizeCode(input: String): String = input.uppercase()
        .filter { it.isLetterOrDigit() }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
        .joinToString("")

    fun link(sealed: SealedBundle, code: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val wrapped = encrypt(codeKey(code, salt), sealed.key, random)
        return LINK_PREFIX + "v=1&s=${b64.encodeToString(salt)}&w=${b64.encodeToString(wrapped)}" +
            "&d=${b64.encodeToString(sealed.cipher)}"
    }

    /** Whether [text] looks like a transfer link (whitespace from chat apps is ignored). */
    fun isLink(text: String): Boolean = text.filterNot { it.isWhitespace() }.contains(LINK_PREFIX)

    fun openLink(text: String, code: String): TransferBundle {
        val compact = text.filterNot { it.isWhitespace() }
        val start = compact.indexOf(LINK_PREFIX)
        if (start < 0) throw TransferException(TransferException.Reason.NOT_A_TRANSFER)
        val params = compact.substring(start + LINK_PREFIX.length).split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
        }.toMap()
        val version = params["v"]?.toIntOrNull() ?: throw TransferException(TransferException.Reason.NOT_A_TRANSFER)
        if (version > TransferBundle.VERSION) throw TransferException(TransferException.Reason.NEWER_VERSION)
        val salt = decode(params["s"])
        val wrapped = decode(params["w"])
        val cipher = decode(params["d"])
        val key = try {
            decrypt(codeKey(code, salt), wrapped)
        } catch (_: GeneralSecurityException) {
            throw TransferException(TransferException.Reason.WRONG_CODE)
        }
        return open(key, cipher)
    }

    private fun codeKey(code: String, salt: ByteArray): ByteArray =
        SCrypt.generate(normalizeCode(code).toByteArray(), salt, 1 shl 15, 8, 1, 32)

    // --- Primitives ---

    private fun decode(text: String?): ByteArray = try {
        unb64.decode(text ?: throw TransferException(TransferException.Reason.DAMAGED))
    } catch (_: IllegalArgumentException) {
        throw TransferException(TransferException.Reason.DAMAGED)
    }

    private fun encrypt(key: ByteArray, plain: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return nonce + cipher.doFinal(plain)
    }

    private fun decrypt(key: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size <= NONCE_BYTES + 16 || key.size != 32) throw TransferException(TransferException.Reason.DAMAGED)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw TransferException(TransferException.Reason.DAMAGED)
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_PLAIN_BYTES) throw TransferException(TransferException.Reason.DAMAGED)
            }
            return out.toByteArray()
        } catch (_: DataFormatException) {
            throw TransferException(TransferException.Reason.DAMAGED)
        } finally {
            inflater.end()
        }
    }

    @kotlinx.serialization.Serializable
    private data class VersionOnly(val v: Int = 0)
}
