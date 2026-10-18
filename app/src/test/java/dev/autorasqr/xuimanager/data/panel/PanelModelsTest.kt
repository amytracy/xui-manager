package dev.autorasqr.xuimanager.data.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelModelsTest {

    private fun settings(port: Int = 2096, tls: Boolean = false, domain: String = "", uri: String = "", path: String = "/sub/") =
        SubscriptionSettings(
            enabled = true,
            port = port,
            path = path,
            domain = domain,
            certFile = if (tls) "/c.pem" else "",
            keyFile = if (tls) "/k.pem" else "",
            uri = uri,
        )

    @Test
    fun `subscription url mirrors BuildSubURIBase`() {
        assertEquals("http://203.0.113.7:2096/sub/abc", settings().url("203.0.113.7", "abc"))
        assertEquals("https://203.0.113.7/sub/abc", settings(port = 443, tls = true).url("203.0.113.7", "abc"))
        assertEquals("http://203.0.113.7/q/abc", settings(port = 80, path = "q").url("203.0.113.7", "abc"))
        assertEquals("https://sub.example.com:8443/sub/abc", settings(port = 8443, tls = true, domain = "sub.example.com").url("1.2.3.4", "abc"))
        assertEquals("http://[2001:db8::1]:2096/sub/abc", settings().url("2001:db8::1", "abc"))
        assertEquals("https://proxy.example.com/s/abc", settings(uri = "https://proxy.example.com/s/").url("1.2.3.4", "abc"))
    }

    @Test
    fun `endpoint base url keeps the secret path and a trailing slash`() {
        assertEquals("https://203.0.113.7:34567/AbC123/", PanelEndpoint("203.0.113.7", 34567, "/AbC123/").baseUrl().toString())
        assertEquals("https://[2001:db8::1]:1/", PanelEndpoint("2001:db8::1", 1, "/").baseUrl().toString())
    }

    @Test
    fun `versions are parsed loosely`() {
        assertEquals(listOf(3, 10, 2), PanelVersion.parse("v3.10.2"))
        assertEquals(listOf(3, 10, 2), PanelVersion.parse("3.10.2-beta"))
        assertEquals(2, PanelVersion.major("2.6.7"))
        assertEquals("v3.10.2", PanelVersion.normalize("3.10.2\n"))
        assertEquals(null, PanelVersion.major("unknown"))
    }

}
