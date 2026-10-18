package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.net.PanelHttpClientFactory
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Exercises the v3 adapter against a TLS MockWebServer, including certificate pinning. */
class V3PanelApiTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    /** Stands in for the system trust store: trusts the test server's certificate and nothing else. */
    private val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
    private val factory = PanelApiFactory(PanelHttpClientFactory(trusting.sslSocketFactory() to trusting.trustManager), json)
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(apiFactory: PanelApiFactory = factory, host: String = "localhost") =
        apiFactory.create("v3.10.2", PanelEndpoint(host, server.port, "/s3cretPath/"), PanelCredentials("tok_test", "admin", null))

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    @Test
    fun `lists clients with bearer token under the base path`() = runBlocking {
        respond(
            """{"success":true,"msg":"","obj":[{"id":1,"email":"anna","subId":"abcd1234","uuid":"u","totalGB":53687091200,
               "expiryTime":1735689600000,"enable":true,"reverse":null,"inboundIds":[3],"traffic":{"up":1024,"down":4096,"enable":true}}]}""",
        )
        val clients = api().listClients()
        assertEquals(1, clients.size)
        with(clients.single()) {
            assertEquals("anna", email)
            assertEquals(53687091200, totalBytes)
            assertEquals(5120, up + down)
            assertEquals(listOf(3), inboundIds)
        }
        val request = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/clients/list", request.url.encodedPath)
        assertEquals("Bearer tok_test", request.headers["Authorization"])
    }

    @Test
    fun `a certificate the system does not trust is refused`() = runBlocking {
        respond("""{"success":true,"obj":[]}""")
        try {
            api(apiFactory = PanelApiFactory(PanelHttpClientFactory(), json)).listClients()
            fail("expected an untrusted certificate")
        } catch (e: Exception) {
            assertEquals(AppError.PanelCertificateInvalid, e.toAppError())
        }
    }

    @Test
    fun `a trusted certificate for another host is refused`() = runBlocking {
        respond("""{"success":true,"obj":[]}""")
        try {
            api(host = "127.0.0.1").listClients()
            fail("expected a hostname mismatch")
        } catch (e: Exception) {
            assertEquals(AppError.PanelCertificateInvalid, e.toAppError())
        }
    }

    @Test
    fun `envelope failure becomes panel error`() = runBlocking {
        respond("""{"success":false,"msg":"inbound 3: email already exists"}""")
        try {
            api().addClient(NewClient("anna", "abcd1234", 0, 0, "xtls-rprx-vision", listOf(3)))
            fail("expected error")
        } catch (e: AppException) {
            assertEquals(AppError.PanelError("inbound 3: email already exists"), e.error)
        }
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("xtls-rprx-vision", body["client"]!!.jsonObject["flow"]!!.jsonPrimitive.content)
        assertEquals(3, body["inboundIds"]!!.jsonArray.single().jsonPrimitive.int)
    }

    @Test
    fun `unauthorized token maps to PanelUnauthorized`() = runBlocking {
        respond("", code = 401)
        try {
            api().status()
            fail("expected 401")
        } catch (e: Exception) {
            assertEquals(AppError.PanelUnauthorized, e.toAppError())
        }
    }

    @Test
    fun `status is parsed from the real field names`() = runBlocking {
        respond(
            """{"success":true,"obj":{"cpu":12.5,"mem":{"current":2147483648,"total":8589934592},"uptime":3600,
               "loads":[0.5,0.3,0.2],"xray":{"state":"running","errorMsg":"","version":"26.10.10"},"panelVersion":"3.10.2"}}""",
        )
        val status = api().status()
        assertEquals(12.5, status.cpuPercent, 0.0)
        assertEquals(3600, status.uptimeSeconds)
        assertEquals(listOf(0.5, 0.3, 0.2), status.loads)
        assertEquals("running", status.xrayState)
    }

    @Test
    fun `subscription update patches only subscription fields`() = runBlocking {
        respond("""{"success":true,"obj":{"webPort":34567,"webBasePath":"/s3cretPath/","subEnable":false,"subPort":2096,"subPath":"/sub/","tgBotEnable":false}}""")
        respond("""{"success":true,"msg":"saved"}""")
        api().updateSubscriptionSettings(SubscriptionUpdate(true, 45678, "/abc/", "/root/cert/ip/fullchain.pem", "/root/cert/ip/privkey.pem"))
        server.takeRequest()
        val update = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/setting/update", update.url.encodedPath)
        val body = json.parseToJsonElement(update.body!!.utf8()).jsonObject
        assertEquals(34567, body["webPort"]!!.jsonPrimitive.int)
        assertEquals(45678, body["subPort"]!!.jsonPrimitive.int)
        assertEquals("true", body["subEnable"]!!.jsonPrimitive.content)
        assertEquals("/root/cert/ip/fullchain.pem", body["subCertFile"]!!.jsonPrimitive.content)
        assertFalse(body["tgBotEnable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `reality inbound payload matches the panel schema`() = runBlocking {
        respond("""{"success":true,"msg":"created","obj":{"id":7,"port":443,"protocol":"vless"}}""")
        val id = api().addInbound(
            InboundSpec(
                remark = "xui-manager-reality",
                port = 443,
                protocol = InboundProtocol.VLESS,
                transport = InboundTransport.TCP,
                reality = RealityParams("www.example.com:443", "www.example.com", "priv", "pub", "0123456789abcdef"),
                path = "",
                shadowsocksPassword = null,
            ),
        )
        assertEquals(7, id)
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val stream = body["streamSettings"]!!.jsonObject
        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)
        val reality = stream["realitySettings"]!!.jsonObject
        assertEquals("www.example.com:443", reality["target"]!!.jsonPrimitive.content)
        assertEquals("www.example.com", reality["serverNames"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("pub", reality["settings"]!!.jsonObject["publicKey"]!!.jsonPrimitive.content)
        assertEquals("none", body["settings"]!!.jsonObject["decryption"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inbounds accept stream settings as object or legacy string`() = runBlocking {
        respond(
            """{"success":true,"obj":[
               {"id":1,"remark":"a","port":443,"protocol":"vless","streamSettings":{"network":"tcp","security":"reality"}},
               {"id":2,"remark":"b","port":8443,"protocol":"vless","streamSettings":"{\"network\":\"ws\",\"security\":\"none\"}"}]}""",
        )
        val inbounds = api().listInbounds()
        assertTrue(inbounds[0].supportsVision)
        assertEquals("ws", inbounds[1].network)
        assertFalse(inbounds[1].supportsVision)
    }

    @Test
    fun `reality scan sends targets as a form field`() = runBlocking {
        respond("""{"success":true,"obj":[{"target":"www.example.com:443","host":"www.example.com","feasible":true,"latencyMs":42}]}""")
        val verdicts = api().scanRealityTargets(listOf("www.example.com", "github.com"))
        assertTrue(verdicts.single().feasible)
        assertEquals("targets=www.example.com%2Cgithub.com", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `panels older than 2_4 are refused`() {
        try {
            factory.create("2.3.10", PanelEndpoint("localhost", 1, "/"), PanelCredentials(null, "admin", "pw"))
            fail("expected unsupported version")
        } catch (e: AppException) {
            assertEquals(AppError.UnsupportedPanelVersion("2.3.10"), e.error)
        }
        assertTrue(PanelVersion.isSupported("v2.4.0"))
        assertFalse(PanelVersion.isSupported("2.3.9"))
        assertTrue(PanelVersion.isSupported("3.0.0"))
    }

    @Test
    fun `update reads the record, posts the full payload and attaches the difference`() = runBlocking {
        respond("""{"success":true,"obj":{"client":{"id":5,"email":"anna","uuid":"u-1","subId":"s1","flow":"","totalGB":0,"expiryTime":0,"enable":true},"inboundIds":[3,4]}}""")
        respond("""{"success":true,"msg":"Client updated"}""")
        respond("""{"success":true}""")
        respond("""{"success":true}""")
        api().updateClient("anna", ClientChanges("anna2", "s1", 0, 0, enabled = true, inboundIds = listOf(3, 7)))

        assertEquals("/s3cretPath/panel/api/clients/get/anna", server.takeRequest().url.encodedPath)
        val update = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/clients/update/anna", update.url.encodedPath)
        val body = json.parseToJsonElement(update.body!!.utf8()).jsonObject
        assertEquals("u-1", body["id"]!!.jsonPrimitive.content)
        assertEquals("anna2", body["email"]!!.jsonPrimitive.content)
        val attach = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/clients/anna2/attach", attach.url.encodedPath)
        assertEquals(listOf(7), json.parseToJsonElement(attach.body!!.utf8()).jsonObject["inboundIds"]!!.jsonArray.map { it.jsonPrimitive.int })
        val detach = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/clients/anna2/detach", detach.url.encodedPath)
        assertEquals(listOf(4), json.parseToJsonElement(detach.body!!.utf8()).jsonObject["inboundIds"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun `delete, reset and toggle use the client endpoints`() = runBlocking {
        respond("""{"success":true}""")
        respond("""{"success":true}""")
        respond("""{"success":true,"obj":{"changed":1,"skipped":[]}}""")
        respond("""{"success":true,"obj":{"changed":0,"skipped":[{"email":"bob","reason":"client not found"}]}}""")
        val api = api()
        api.deleteClient("anna@home")
        api.resetClientTraffic("anna")
        api.setClientEnabled("anna", false)
        try {
            api.setClientEnabled("bob", true)
            fail("expected skip error")
        } catch (e: AppException) {
            assertEquals(AppError.PanelError("client not found"), e.error)
        }
        assertEquals(listOf("panel", "api", "clients", "del", "anna@home"), server.takeRequest().url.pathSegments.drop(1))
        assertEquals("/s3cretPath/panel/api/clients/resetTraffic/anna", server.takeRequest().url.encodedPath)
        val disable = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/clients/bulkDisable", disable.url.encodedPath)
        assertEquals("anna", json.parseToJsonElement(disable.body!!.utf8()).jsonObject["emails"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("/s3cretPath/panel/api/clients/bulkEnable", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `inbound get, update, enable and delete use the v3 routes`() = runBlocking {
        respond("""{"success":true,"obj":{"id":9,"remark":"r","port":443,"protocol":"vless","enable":true,
            "settings":{"clients":[{"email":"a"}],"decryption":"none"},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"target":"www.microsoft.com:443","serverNames":["www.microsoft.com"],"privateKey":"priv","shortIds":["ab"],"settings":{"publicKey":"pub","fingerprint":"chrome"}}},
            "sniffing":"{\"enabled\":true}"}}""")
        respond("""{"success":true}""")
        respond("""{"success":true}""")
        respond("""{"success":true}""")
        val api = api()
        val inbound = api.getInbound(9)
        assertTrue("sniffing string is normalized to an object", inbound["sniffing"] is JsonObject)
        api.updateInbound(9, InboundJson.patchReality(inbound, "127.0.0.1:8443", "vpn.example.com", "firefox"))
        api.setInboundEnabled(9, false)
        api.deleteInbound(9)

        assertEquals("/s3cretPath/panel/api/inbounds/get/9", server.takeRequest().url.encodedPath)
        val update = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/inbounds/update/9", update.url.encodedPath)
        val reality = json.parseToJsonElement(update.body!!.utf8()).jsonObject["streamSettings"]!!.jsonObject["realitySettings"]!!.jsonObject
        assertEquals("127.0.0.1:8443", reality["target"]!!.jsonPrimitive.content)
        assertEquals("vpn.example.com", reality["serverNames"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("priv", reality["privateKey"]!!.jsonPrimitive.content)
        assertEquals("ab", reality["shortIds"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("firefox", reality["settings"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("pub", reality["settings"]!!.jsonObject["publicKey"]!!.jsonPrimitive.content)
        val enable = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/inbounds/setEnable/9", enable.url.encodedPath)
        assertEquals("""{"enable":false}""", enable.body!!.utf8())
        assertEquals("/s3cretPath/panel/api/inbounds/del/9", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `xray template is unwrapped from the JSON string and saved as form fields`() = runBlocking {
        respond("""{"success":true,"obj":"{\"xraySetting\":{\"routing\":{\"rules\":[]},\"outbounds\":[]},\"inboundTags\":[],\"outboundTestUrl\":\"https://t.example/204\"}"}""")
        respond("""{"success":true}""")
        val api = api()
        assertTrue(api.capabilities.routing)
        val template = api.xrayTemplate()
        assertEquals("https://t.example/204", template.outboundTestUrl)
        assertTrue(template.config["routing"] is JsonObject)
        api.saveXrayTemplate(template)

        assertEquals("/s3cretPath/panel/api/xray/", server.takeRequest().url.encodedPath)
        val save = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/xray/update", save.url.encodedPath)
        val form = save.body!!.utf8()
        assertTrue(form, form.contains("xraySetting=%7B%22routing%22"))
        assertTrue(form, form.contains("outboundTestUrl=https%3A%2F%2Ft.example%2F204"))
    }

    @Test
    fun `patching settings keeps every other setting`() = runBlocking {
        respond("""{"success":true,"obj":{"subEnable":true,"subPort":2096,"webPort":1234,"subEnableRouting":false,"subRoutingRules":""}}""")
        respond("""{"success":true}""")
        api().patchSettings(mapOf("subEnableRouting" to kotlinx.serialization.json.JsonPrimitive(true)))
        server.takeRequest()
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("true", body["subEnableRouting"]!!.jsonPrimitive.content)
        assertEquals(1234, body["webPort"]!!.jsonPrimitive.int)
        assertEquals(2096, body["subPort"]!!.jsonPrimitive.int)
    }

    @Test
    fun `editor endpoints use the v3 routes and shapes`() = runBlocking {
        respond("""{"success":true,"obj":{"id":42}}""")
        respond("""{"success":true,"obj":{"auths":[{"id":"x25519","label":"X25519, not Post-Quantum","decryption":"mlkem768x25519plus.native.600s.dec","encryption":"mlkem768x25519plus.native.0rtt.enc"}]}}""")
        respond("""{"success":true,"obj":{"target":"www.apple.com:443","feasible":true,"tls13":true,"h2":true,"serverNames":["www.apple.com"]}}""")
        respond("""{"success":true,"obj":{"echServerKeys":"KEYS","echConfigList":"LIST"}}""")
        respond("""{"success":true}""")
        val api = api()
        val id = api.addInboundRaw(
            json.parseToJsonElement("""{"remark":"x","port":2000,"protocol":"vless","settings":{"clients":[]},"streamSettings":{"network":"tcp"},"sniffing":{"enabled":false}}""").jsonObject,
        )
        assertEquals(42, id)
        assertEquals("mlkem768x25519plus.native.0rtt.enc", api.newVlessEncAuths().single().encryption)
        assertTrue(api.scanRealityTarget("www.apple.com:443", "www.apple.com", 0, false)["feasible"].toString() == "true")
        assertEquals("KEYS" to "LIST", api.newEchCert("vpn.example.com"))
        api.setFallbacks(7, listOf(json.parseToJsonElement("""{"childId":3,"name":"","alpn":"h2","path":"","dest":"","xver":0,"sortOrder":0}""").jsonObject))

        val add = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/inbounds/add", add.url.encodedPath)
        assertTrue("settings travel as objects", json.parseToJsonElement(add.body!!.utf8()).jsonObject["settings"] is JsonObject)
        assertEquals("/s3cretPath/panel/api/server/getNewVlessEnc", server.takeRequest().url.encodedPath)
        val scan = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/server/scanRealityTarget", scan.url.encodedPath)
        assertEquals("target=www.apple.com%3A443&sni=www.apple.com&xver=0&allowPrivate=false", scan.body!!.utf8())
        assertEquals("sni=vpn.example.com", server.takeRequest().body!!.utf8())
        val fallbacks = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/inbounds/7/fallbacks", fallbacks.url.encodedPath)
        assertTrue(fallbacks.body!!.utf8().startsWith("{\"fallbacks\":[{\"childId\":3"))
    }

    private fun node(token: String? = "tok_node", scheme: String = "https", tls: String = "verify", sync: String = "all") = NodeMutation(
        name = " de-fra-1 ", remark = "", scheme = scheme, address = "node.example.com", port = 2053, basePath = "",
        apiToken = token, enabled = true, allowPrivateAddress = false, tlsVerifyMode = tls, pinnedCertSha256 = "PIN=",
        inboundSyncMode = sync, inboundTags = listOf("in-443-tcp"), outboundTag = "",
    )

    @Test
    fun `lists nodes with health and inbound placement`() = runBlocking {
        respond(
            """{"success":true,"obj":[{"id":1,"name":"edge-1","scheme":"https","address":"node.example.com","port":2053,"basePath":"/",
               "enable":true,"status":"online","latencyMs":42,"cpuPct":12.5,"memPct":45.2,"uptimeSecs":86400,"xrayState":"running",
               "xrayVersion":"25.10.31","panelVersion":"v3.10.2","inboundCount":3,"clientCount":25,"onlineCount":5,"hasApiToken":true,
               "tlsVerifyMode":"verify","inboundSyncMode":"all","inboundTags":null,"transitive":false},
               {"id":0,"name":"sub","status":"","transitive":true}]}""",
        )
        respond("""{"success":true,"obj":[{"id":7,"remark":"edge","port":443,"protocol":"vless","nodeId":1,"streamSettings":{"network":"tcp","security":"reality"}},
               {"id":8,"remark":"local","port":443,"protocol":"vless","nodeId":null}]}""")
        val api = api()
        assertTrue(api.capabilities.nodes)
        val nodes = api.listNodes()
        assertEquals("/s3cretPath/panel/api/nodes/list", server.takeRequest().url.encodedPath)
        with(nodes.first()) {
            assertEquals("online", status)
            assertEquals(42L, latencyMs)
            assertEquals(emptyList<String>(), inboundTags)
            assertTrue(hasApiToken)
        }
        assertEquals("unknown", nodes[1].status)
        assertTrue(nodes[1].transitive)
        val inbounds = api.listInbounds()
        assertEquals(1, inbounds.first { it.id == 7 }.nodeId)
        assertEquals(null, inbounds.first { it.id == 8 }.nodeId)
    }

    @Test
    fun `adding a node sends the panel form's shape and returns its id`() = runBlocking {
        respond("""{"success":true,"msg":"Node added","obj":{"id":5,"name":"de-fra-1","status":"online"}}""")
        assertEquals(5, api().addNode(node()))
        val request = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/nodes/add", request.url.encodedPath)
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("de-fra-1", body["name"]!!.jsonPrimitive.content)
        assertEquals("/", body["basePath"]!!.jsonPrimitive.content)
        assertEquals("tok_node", body["apiToken"]!!.jsonPrimitive.content)
        assertEquals("verify", body["tlsVerifyMode"]!!.jsonPrimitive.content)
        assertEquals("a pin is sent only in pin mode", "", body["pinnedCertSha256"]!!.jsonPrimitive.content)
        assertEquals("tags are sent only for selected sync", 0, body["inboundTags"]!!.jsonArray.size)
        assertEquals(0, body["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `updating keeps the stored token and plain http never verifies`() = runBlocking {
        respond("""{"success":true}""")
        api().updateNode(5, node(token = null, scheme = "http", tls = "pin", sync = "selected"))
        val request = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/nodes/update/5", request.url.encodedPath)
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertFalse("no token means keep the stored one", "apiToken" in body)
        assertEquals("skip", body["tlsVerifyMode"]!!.jsonPrimitive.content)
        assertEquals("", body["pinnedCertSha256"]!!.jsonPrimitive.content)
        assertEquals(listOf("in-443-tcp"), body["inboundTags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(5, body["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `node probes, pins, inbounds and removal use their routes`() = runBlocking {
        respond("""{"success":true,"obj":{"status":"offline","error":"dial tcp: i/o timeout","latencyMs":0}}""")
        respond("""{"success":true,"obj":{"status":"online","latencyMs":17,"xrayVersion":"25.10.31","error":""}}""")
        respond("""{"success":true,"obj":"k3b1base64sha256="}""")
        respond("""{"success":true,"obj":[{"tag":"inbound-443","remark":"VLESS","protocol":"vless","port":443},{"tag":""}]}""")
        respond("""{"success":true}""")
        respond("""{"success":true}""")
        respond("""{"success":true,"obj":{"webCertFile":"/root/cert/n/fullchain.pem","webKeyFile":"/root/cert/n/privkey.pem"}}""")
        respond("""{"success":false,"msg":"Add node: node unreachable"}""")
        val api = api()
        val test = api.testNode(node(tls = "pin"))
        assertFalse(test.online)
        assertEquals("dial tcp: i/o timeout", test.error)
        assertEquals(17L, api.probeNode(5).latencyMs)
        assertEquals("k3b1base64sha256=", api.nodeCertFingerprint(node(tls = "pin")))
        assertEquals(listOf("inbound-443"), api.nodeRemoteInbounds(node()).map { it.tag })
        api.setNodeEnabled(5, false)
        api.deleteNode(5)
        assertEquals("/root/cert/n/fullchain.pem" to "/root/cert/n/privkey.pem", api.nodeWebCert(5))
        try {
            api.addNode(node())
            fail("expected the panel's refusal")
        } catch (e: AppException) {
            assertEquals(AppError.PanelError("Add node: node unreachable"), e.error)
        }

        val testRequest = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/nodes/test", testRequest.url.encodedPath)
        assertEquals("PIN=", json.parseToJsonElement(testRequest.body!!.utf8()).jsonObject["pinnedCertSha256"]!!.jsonPrimitive.content)
        assertEquals("/s3cretPath/panel/api/nodes/probe/5", server.takeRequest().url.encodedPath)
        assertEquals("/s3cretPath/panel/api/nodes/certFingerprint", server.takeRequest().url.encodedPath)
        assertEquals("/s3cretPath/panel/api/nodes/inbounds", server.takeRequest().url.encodedPath)
        val enable = server.takeRequest()
        assertEquals("/s3cretPath/panel/api/nodes/setEnable/5", enable.url.encodedPath)
        assertEquals("""{"enable":false}""", enable.body!!.utf8())
        assertEquals("/s3cretPath/panel/api/nodes/del/5", server.takeRequest().url.encodedPath)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `an inbound for a node carries its id and advertises the node's address`() = runBlocking {
        respond("""{"success":true,"obj":{"id":9}}""")
        val spec = InboundSpec(
            remark = "edge", port = 443, protocol = InboundProtocol.VLESS, transport = InboundTransport.TCP,
            reality = RealityParams("www.apple.com:443", "www.apple.com", "priv", "pub", "abcd", "chrome"),
            path = "", shadowsocksPassword = null, nodeId = 3,
        )
        assertEquals(9, api().addInbound(spec))
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(3, body["nodeId"]!!.jsonPrimitive.int)
        assertEquals("node", body["shareAddrStrategy"]!!.jsonPrimitive.content)
    }
}
