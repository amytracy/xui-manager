package dev.autorasqr.xuimanager.data.inbound.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InboundDraftTest {
    private fun p(path: String) = JsonPath(path)

    @Test
    fun `a new inbound is VLESS over RAW with an automatic tag that follows the port`() {
        val d = InboundDraft.new(23456)
        assertEquals("vless", d.protocol)
        assertEquals("tcp", d.network)
        assertEquals("none", d.security)
        assertEquals("in-23456-tcp", d[p("tag")].str())
        assertEquals("in-31000-tcp", d.set(p("port"), json(31000))[p("tag")].str())
        val custom = d.set(p("tag"), json("my-tag")).set(p("port"), json(31000))
        assertEquals("a hand-written tag is kept", "my-tag", custom[p("tag")].str())
    }

    @Test
    fun `switching to Hysteria brings QUIC TLS and a salamander mask, Shadowsocks tags tcp and udp`() {
        val h = InboundDraft.new(1000).withProtocol("hysteria")
        assertEquals("hysteria", h.network)
        assertEquals("tls", h.security)
        assertEquals(listOf("h3"), h[p("streamSettings.tlsSettings.alpn")].strings())
        assertEquals("salamander", h[p("streamSettings.finalmask.udp.0.type")].str())
        assertEquals("in-1000-udp", h[p("tag")].str())

        val ss = InboundDraft.new(1000).withProtocol("shadowsocks")
        assertEquals("2022-blake3-aes-256-gcm", ss[p("settings.method")].str())
        assertEquals(32, Base64.getDecoder().decode(ss[p("settings.password")].str()).size)
        assertEquals("in-1000-tcpudp", ss[p("tag")].str())

        val back = h.withProtocol("vless")
        assertEquals("leaving hysteria resets the stream to RAW", "tcp", back.network)
        assertEquals("none", back.security)
    }

    @Test
    fun `the protocol of a saved inbound cannot change`() {
        val saved = InboundDraft.fromPanel(InboundDraft.new(1000).inbound)
        assertEquals("vless", saved.withProtocol("trojan").protocol)
    }

    @Test
    fun `network switches swap the per-network settings and keep the mKCP mask in step`() {
        val kcp = InboundDraft.new(1000).withNetwork("kcp")
        assertNull(kcp[p("streamSettings.tcpSettings")])
        assertEquals(1350, kcp[p("streamSettings.kcpSettings.mtu")].int())
        assertEquals("mkcp-legacy", kcp[p("streamSettings.finalmask.udp.0.type")].str())
        assertEquals("in-1000-udp", kcp[p("tag")].str())
        val ws = kcp.withSecurity("tls").withNetwork("ws")
        assertEquals(JsonArray(emptyList()), ws[p("streamSettings.finalmask.udp")])
        assertEquals("WebSocket over TLS speaks HTTP/1.1", listOf("http/1.1"), ws[p("streamSettings.tlsSettings.alpn")].strings())
        val xdrive = ws.withNetwork("xdrive")
        assertEquals("none", xdrive.security)
        assertNull(xdrive[p("streamSettings.tlsSettings")])
    }

    @Test
    fun `security switches seed TLS and Reality like the panel`() {
        val tls = InboundDraft.new(1000).withSecurity("tls")
        assertEquals("chrome", tls[p("streamSettings.tlsSettings.settings.fingerprint")].str())
        assertEquals(true, tls[p("streamSettings.tlsSettings.certificates.0.useFile")].bool())
        val reality = tls.withSecurity("reality")
        assertNull(reality[p("streamSettings.tlsSettings")])
        val shortIds = reality[p("streamSettings.realitySettings.shortIds")].strings()
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), shortIds.map { it.length }.sorted())
        assertTrue(reality[p("streamSettings.realitySettings.settings.spiderX")].str()!!.matches(Regex("/[A-Za-z0-9]{15}")))
    }

    @Test
    fun `panel rows are healed and filled with defaults, unknown keys survive`() {
        val raw = Json.parseToJsonElement(
            """{"id":5,"protocol":"vless","port":443,"remark":"r","enable":true,"clientStats":[],
               "settings":{"clients":[{"id":"u"}],"decryption":"none","futureKey":1},
               "streamSettings":{"method":"tcp","security":"reality","realitySettings":{"dest":"www.apple.com:443","serverNames":["www.apple.com"],"privateKey":"k"},"custom":{"a":1}},
               "sniffing":{"enabled":true,"destOverride":["http"]}}""",
        ).jsonObject
        val d = InboundDraft.fromPanel(raw)
        assertFalse(d.isNew)
        assertEquals("tcp", d.network)
        assertEquals("www.apple.com:443", d[p("streamSettings.realitySettings.target")].str())
        assertNull(d[p("streamSettings.realitySettings.dest")])
        assertEquals("chrome", d[p("streamSettings.realitySettings.settings.fingerprint")].str())
        assertEquals("none", d[p("streamSettings.tcpSettings.header.type")].str())
        assertNull("clients have their own API", d[p("settings.clients")])
        assertNull(d[p("id")])
        assertEquals(1, d[p("settings.futureKey")].int())
        assertEquals(1, d[p("streamSettings.custom.a")].int())
        assertEquals(listOf("http"), d[p("sniffing.destOverride")].strings())
        assertEquals("never", d[p("trafficReset")].str())
    }

    @Test
    fun `the wire payload drops defaults the core does not need`() {
        var d = InboundDraft.new(1000).withNetwork("xhttp").withSockopt(true)
        d = d.set(p("streamSettings.xhttpSettings.mode"), json("stream-one"))
            .set(p("streamSettings.sockopt.mark"), json(255))
            .set(p("sniffing.enabled"), json(true))
        val wire = InboundWire.payload(d)
        val stream = wire["streamSettings"]!!.jsonObject
        val xhttp = stream["xhttpSettings"]!!.jsonObject
        assertFalse("stream-one has no packet-up fields", "scMaxBufferedPosts" in xhttp)
        assertFalse("scStreamUpServerSecs" in xhttp)
        assertFalse("enableXmux" in xhttp)
        assertFalse("headers" in xhttp)
        assertEquals(JsonObject(mapOf("mark" to JsonPrimitive(255), "tcpcongestion" to JsonPrimitive("bbr"))), stream["sockopt"])
        assertNull("empty fallbacks are not sent", wire["settings"]!!.jsonObject["fallbacks"])
        val sniffing = wire["sniffing"]!!.jsonObject
        assertEquals(setOf("enabled", "destOverride"), sniffing.keys)
        assertEquals(JsonArray(emptyList()), wire["settings"]!!.jsonObject["clients"])

        val saved = InboundWire.payload(InboundDraft.fromPanel(d.inbound))
        assertNull("an edit sends no clients", saved["settings"]!!.jsonObject["clients"])
        assertEquals(JsonPrimitive(""), InboundWire.payload(InboundDraft.new(1).withProtocol("mtproto"))["sniffing"])
    }

    @Test
    fun `TLS certificates send only the half the switch selects`() {
        val d = InboundDraft.new(1000).withSecurity("tls")
            .set(p("streamSettings.tlsSettings.certificates.0.certificateFile"), json("/root/cert/fullchain.pem"))
            .set(p("streamSettings.tlsSettings.certificates.0.keyFile"), json("/root/cert/privkey.pem"))
        val cert = InboundWire.payload(d)["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject["certificates"]!!.let { (it as JsonArray)[0].jsonObject }
        assertEquals("/root/cert/fullchain.pem", cert["certificateFile"].str())
        assertFalse("useFile" in cert)
        assertFalse("certificate" in cert)
        assertEquals(null, InboundValidator.validate(d))
    }

    @Test
    fun `validation stops what the panel would reject`() {
        val base = InboundDraft.new(1000)
        assertEquals("port", InboundValidator.validate(base.set(p("port"), json(0)))?.code)
        assertNull("a unix socket listen allows port 0", InboundValidator.validate(base.set(p("listen"), json("/dev/shm/x.sock")).set(p("port"), json(0))))
        assertEquals("tlsCert", InboundValidator.validate(base.withSecurity("tls"))?.code)
        val reality = base.withSecurity("reality")
        assertEquals("realityTarget", InboundValidator.validate(reality)?.code)
        val good = reality.set(p("streamSettings.realitySettings.target"), json("www.apple.com:443"))
            .set(p("streamSettings.realitySettings.serverNames"), jsonStrings(listOf("www.apple.com")))
            .set(p("streamSettings.realitySettings.privateKey"), json("k"))
        assertNull(InboundValidator.validate(good))
        assertEquals("clientVer", InboundValidator.validate(good.set(p("streamSettings.realitySettings.minClientVer"), json("1.300")))?.code)
        assertTrue(InboundValidator.validRealityTarget("8443"))
        assertFalse(InboundValidator.validRealityTarget("example.com"))
        assertEquals(listOf(25, 9, 0), InboundValidator.clientVer("25.9"))
    }

    @Test
    fun `wireguard public keys follow RFC 7748`() {
        val private = Base64.getEncoder().encodeToString(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val public = Base64.getEncoder().encodeToString(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"))
        assertEquals(public, EditorDefaults.wireguardPublicKey(private))
        val (genPrivate, genPublic) = EditorDefaults.wireguardKeyPair()
        assertEquals(genPublic, EditorDefaults.wireguardPublicKey(genPrivate))
        val awg = InboundDraft.new(1).withProtocol("amneziawg").set(p("settings.server.privateKey"), json(private))
        assertEquals("AmneziaWG keeps its stored public key in step", public, awg[p("settings.server.publicKey")].str())
    }

    @Test
    fun `json paths create and remove nested values`() {
        val o = JsonObject(emptyMap()).with(p("a.b.0.c"), json(1))
        assertEquals(1, o.at(p("a.b.0.c")).int())
        assertEquals(JsonObject(mapOf("a" to JsonObject(mapOf("b" to JsonArray(emptyList()))))), o.with(p("a.b.0"), null))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `placing a new inbound on a node prefixes its tag and advertises the node`() {
        val d = InboundDraft.new(443).withNode(3)
        assertEquals(3, d.nodeId)
        assertEquals("n3-in-443-tcp", d[p("tag")].str())
        assertEquals("node", d[p("shareAddrStrategy")].str())
        val back = d.withNode(null)
        assertNull(back.nodeId)
        assertNull("the key is dropped, not sent as null", back.inbound["nodeId"])
        assertEquals("in-443-tcp", back[p("tag")].str())
        assertEquals("listen", back[p("shareAddrStrategy")].str())
        val custom = InboundDraft.new(443).set(p("shareAddrStrategy"), json("custom")).withNode(3)
        assertEquals("a custom address is kept", "custom", custom[p("shareAddrStrategy")].str())
    }

    @Test
    fun `only node protocols can live on a node and a saved inbound stays where it is`() {
        val onNode = InboundDraft.new(1080).withNode(2)
        assertNull("mixed is panel-local", onNode.withProtocol("mixed").nodeId)
        assertEquals(2, onNode.withProtocol("trojan").nodeId)
        assertNull(InboundDraft.new(1080).withProtocol("mixed").withNode(2).nodeId)
        val saved = InboundDraft.fromPanel(Json.parseToJsonElement("""{"id":1,"port":443,"protocol":"vless","nodeId":2,"tag":"n2-in-443-tcp","settings":{}}""").jsonObject)
        assertEquals(2, saved.nodeId)
        assertEquals(2, saved.withNode(null).nodeId)
    }
}
