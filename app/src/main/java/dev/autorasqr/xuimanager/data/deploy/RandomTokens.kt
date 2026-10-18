package dev.autorasqr.xuimanager.data.deploy

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RandomTokens @Inject constructor() {
    private val random = SecureRandom()

    fun alphanumeric(length: Int): String = pick(ALNUM, length)

    fun lowerAlphanumeric(length: Int): String = pick(LOWER_ALNUM, length)

    fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun port(from: Int = 20_000, until: Int = 60_000): Int = from + random.nextInt(until - from)

    /** Standard base64 of [bytes] random bytes, the key format Shadowsocks 2022 expects. */
    fun base64Key(bytes: Int): String = java.util.Base64.getEncoder().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun pick(alphabet: String, length: Int) = buildString(length) {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

    private companion object {
        const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val LOWER_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
