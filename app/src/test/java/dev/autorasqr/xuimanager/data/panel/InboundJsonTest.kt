package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundJsonTest {

    private val reality = RealityParams("www.example.com:443", "www.example.com", "priv", "pub", "0123456789abcdef")

    private fun spec(protocol: InboundProtocol, transport: InboundTransport, withReality: Boolean = true) = InboundSpec(
        remark = "r",
        port = 443,
        protocol = protocol,
        transport = transport,
        reality = if (withReality) reality else null,
        path = if (transport == InboundTransport.GRPC) "svc" else "/p",
        shadowsocksPassword = if (protocol == InboundProtocol.SHADOWSOCKS) "a2V5" else null,
    )

    @Test
    fun `vless reality over grpc`() {
        val s = spec(InboundProtocol.VLESS, InboundTransport.GRPC)
        assertEquals("none", InboundJson.settings(s)["decryption"]!!.jsonPrimitive.content)
        val stream = InboundJson.streamSettings(s)
        assertEquals("grpc", stream["network"]!!.jsonPrimitive.content)
        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)
        assertEquals("svc", stream["grpcSettings"]!!.jsonObject["serviceName"]!!.jsonPrimitive.content)
        assertEquals("www.example.com", stream["realitySettings"]!!.jsonObject["serverNames"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse("tcpSettings" in stream)
    }

    @Test
    fun `reality fingerprint is written for client links`() {
        val s = spec(InboundProtocol.VLESS, InboundTransport.TCP).copy(reality = reality.copy(fingerprint = "firefox"))
        val settings = InboundJson.streamSettings(s)["realitySettings"]!!.jsonObject["settings"]!!.jsonObject
        assertEquals("firefox", settings["fingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan reality over xhttp`() {
        val s = spec(InboundProtocol.TROJAN, InboundTransport.XHTTP)
        assertTrue("fallbacks" in InboundJson.settings(s))
        val xhttp = InboundJson.streamSettings(s)["xhttpSettings"]!!.jsonObject
        assertEquals("/p", xhttp["path"]!!.jsonPrimitive.content)
        assertEquals("auto", xhttp["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vmess over websocket without tls`() {
        val s = spec(InboundProtocol.VMESS, InboundTransport.WS, withReality = false)
        val stream = InboundJson.streamSettings(s)
        assertEquals("none", stream["security"]!!.jsonPrimitive.content)
        assertFalse("realitySettings" in stream)
        assertEquals("/p", stream["wsSettings"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(setOf("clients"), InboundJson.settings(s).keys)
    }

    @Test
    fun `shadowsocks 2022 carries method and server key`() {
        val settings = InboundJson.settings(spec(InboundProtocol.SHADOWSOCKS, InboundTransport.TCP, withReality = false))
        assertEquals(InboundJson.SS_METHOD, settings["method"]!!.jsonPrimitive.content)
        assertEquals("a2V5", settings["password"]!!.jsonPrimitive.content)
        assertEquals("tcp,udp", settings["network"]!!.jsonPrimitive.content)
    }
}
