package dev.autorasqr.xuimanager.data.deploy

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The user's own index.html for the self-steal site: one self-contained HTML file. */
object SitePage {
    const val MAX_BYTES = 1 shl 20

    /** Accepts non-empty UTF-8 text of at most [MAX_BYTES] that looks like an HTML page. */
    fun validate(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return false
        }
        if ('\u0000' in text) return false
        val lower = text.lowercase()
        return "<html" in lower || "<!doctype html" in lower
    }
}
