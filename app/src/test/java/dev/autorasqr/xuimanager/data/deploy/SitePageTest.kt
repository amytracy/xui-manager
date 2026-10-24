package dev.autorasqr.xuimanager.data.deploy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePageTest {

    @Test
    fun `a self-contained HTML page is accepted`() {
        assertTrue(SitePage.validate("<!DOCTYPE html>\n<html><body>Привет</body></html>".toByteArray()))
        assertTrue(SitePage.validate("<HTML><head><style>body{}</style></head></HTML>".toByteArray()))
    }

    @Test
    fun `empty, oversized and non-HTML files are refused`() {
        assertFalse(SitePage.validate(ByteArray(0)))
        assertFalse(SitePage.validate("just some notes".toByteArray()))
        val big = "<html>" + "a".repeat(SitePage.MAX_BYTES) + "</html>"
        assertFalse(SitePage.validate(big.toByteArray()))
    }

    @Test
    fun `binary files are refused even with an html tag inside`() {
        assertFalse(SitePage.validate("<html>".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(SitePage.validate("<html>\u0000</html>".toByteArray()))
    }
}
