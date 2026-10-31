package dev.autorasqr.xuimanager.data.geo

import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.PanelProvisioner
import dev.autorasqr.xuimanager.data.panel.PanelInbound
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerCountryTest {
    private val de = "🇩🇪"

    @Test
    fun emojiFromCode() {
        assertEquals(de, Flags.emoji("DE"))
        assertEquals(de, Flags.emoji(" de "))
        assertEquals(Flags.EU, Flags.emoji(null))
        assertEquals(Flags.EU, Flags.emoji(""))
        assertEquals(Flags.EU, Flags.emoji("D1"))
        assertEquals(Flags.EU, Flags.emoji("DEU"))
        assertEquals("🇪🇺", Flags.EU)
    }

    @Test
    fun prefixAndStrip() {
        assertEquals("$de xui-manager-reality", Flags.prefix("xui-manager-reality", de))
        assertEquals("$de xui-manager-reality", Flags.prefix("$de xui-manager-reality", Flags.EU))
        assertEquals(de, Flags.prefix("", de))
        assertTrue(Flags.startsWithFlag("$de x"))
        assertFalse(Flags.startsWithFlag("x $de"))
        assertFalse(Flags.startsWithFlag("🇩"))
        assertEquals("xui-manager-reality", Flags.strip("$de xui-manager-reality"))
        assertEquals("xui-manager-reality", Flags.strip("xui-manager-reality"))
        assertEquals("", Flags.strip(de))
    }

    @Test
    fun defaultInboundIgnoresFlag() {
        val inbounds = listOf(
            inbound(1, "other", "vless"),
            inbound(2, "${Flags.EU} ${DeployConfig.INBOUND_REMARK}", "vless"),
            inbound(3, "$de ${DeployConfig.SELFSTEAL_REMARK}", "vless"),
        )
        assertEquals(3, PanelProvisioner.defaultInbound(inbounds)?.id)
        assertEquals(2, PanelProvisioner.defaultInbound(inbounds.take(2))?.id)
    }

    private fun inbound(id: Int, remark: String, protocol: String) = PanelInbound(
        id = id, remark = remark, port = 443 + id, protocol = protocol, network = "tcp", security = "reality",
    )

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun lookup(resolve: (String) -> String? = { "1.2.3.4" }) =
        HttpCountryLookup(OkHttpClient(), server.url("/a/").toString(), server.url("/b/").toString(), resolve)

    private fun reply(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    @Test
    fun primaryAnswers() = runBlocking {
        reply(200, """{"ip":"1.2.3.4","country":"nl"}""")
        val lookup = lookup()
        assertEquals("NL", lookup.countryCode("example.com"))
        assertEquals("/a/1.2.3.4", server.takeRequest().url.encodedPath)
        // Cached: no second request.
        assertEquals("NL", lookup.countryCode("EXAMPLE.com"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fallbackWhenPrimaryFails() = runBlocking {
        reply(429, """{"error":"rate"}""")
        reply(200, """{"success":true,"country_code":"FI"}""")
        assertEquals("FI", lookup().countryCode("1.2.3.4"))
    }

    @Test
    fun nothingKnown() = runBlocking {
        reply(500, "")
        reply(200, """{"success":false,"message":"Invalid IP"}""")
        val lookup = lookup()
        assertNull(lookup.countryCode("1.2.3.4"))
        assertNull(lookup { null }.countryCode("nowhere.invalid"))
        assertEquals(Flags.EU, lookup { throw java.net.UnknownHostException() }.flag("nowhere.invalid"))
    }
}

class FlagRenamesTest {
    private val de = "\uD83C\uDDE9\uD83C\uDDEA"

    private fun inbound(id: Int, remark: String, nodeId: Int? = null) = dev.autorasqr.xuimanager.data.panel.PanelInbound(
        id = id, remark = remark, port = 443 + id, protocol = "vless", network = "tcp", security = "reality", nodeId = nodeId,
    )

    @Test
    fun picksUnflaggedWithTheirHost() {
        val inbounds = listOf(
            inbound(1, "$de flagged"),
            inbound(2, "plain"),
            inbound(3, "on node", nodeId = 7),
            inbound(4, "unknown node", nodeId = 8),
            inbound(5, "$de node flagged", nodeId = 7),
            inbound(6, "", nodeId = 9),
        )
        val renames = dev.autorasqr.xuimanager.data.repository.flagRenames(
            inbounds, mapOf(7 to "5.6.7.8", 9 to " ")::get, "1.2.3.4",
        )
        assertEquals(listOf(2 to "1.2.3.4", 3 to "5.6.7.8", 4 to "1.2.3.4", 6 to "1.2.3.4"), renames.map { it.first.id to it.second })
    }
}
