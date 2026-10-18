package dev.autorasqr.xuimanager.data.panel.v2

import dev.autorasqr.xuimanager.data.panel.ClientChanges
import dev.autorasqr.xuimanager.data.panel.NewClient
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiFactory
import dev.autorasqr.xuimanager.data.panel.PanelCredentials
import dev.autorasqr.xuimanager.data.panel.PanelEndpoint
import dev.autorasqr.xuimanager.data.panel.net.PanelHttpClientFactory
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Exercises the 2.x adapter: login session, clients stored inside inbound settings, and both route layouts. */
class V2PanelApiTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
    private val factory = PanelApiFactory(PanelHttpClientFactory(trusting.sslSocketFactory() to trusting.trustManager), json)
    private val server = MockWebServer()

    private val inbounds = """{"success":true,"obj":[
        {"id":3,"remark":"main","port":443,"protocol":"vless",
         "settings":"{\"clients\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"flow\":\"xtls-rprx-vision\",\"email\":\"anna\",\"limitIp\":0,\"totalGB\":0,\"expiryTime\":0,\"enable\":true,\"tgId\":\"\",\"subId\":\"sub1\",\"reset\":0,\"custom\":\"keep\"}],\"decryption\":\"none\",\"fallbacks\":[]}",
         "streamSettings":"{\"network\":\"tcp\",\"security\":\"reality\"}",
         "clientStats":[{"email":"anna","up":100,"down":200,"enable":true}]},
        {"id":4,"remark":"tj","port":8443,"protocol":"trojan",
         "settings":"{\"clients\":[{\"password\":\"p@ss/1\",\"email\":\"bob\",\"enable\":false,\"totalGB\":1073741824,\"expiryTime\":0,\"subId\":\"sub2\"}]}",
         "streamSettings":"{\"network\":\"grpc\",\"security\":\"reality\"}","clientStats":[]},
        {"id":5,"remark":"ss","port":8388,"protocol":"shadowsocks",
         "settings":"{\"method\":\"2022-blake3-aes-256-gcm\",\"password\":\"k\",\"clients\":[]}",
         "streamSettings":"{\"network\":\"tcp\",\"security\":\"none\"}","clientStats":[]}
    ]}"""

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(version: String = "2.9.4"): PanelApi = factory.create(
        version,
        PanelEndpoint("localhost", server.port, "/v2path/"),
        PanelCredentials(apiToken = null, username = "admin", password = "s3cret"),
    )

    private fun respond(body: String, code: Int = 200, cookie: String? = null) {
        val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        if (cookie != null) builder.addHeader("Set-Cookie", cookie)
        server.enqueue(builder.build())
    }

    private fun loginOk() = respond("""{"success":true,"msg":"Login successful"}""", cookie = "3x-ui=session-1; Path=/; HttpOnly; Secure")

    private fun RecordedRequest.form(name: String): String? = ("http://x/?" + body!!.utf8()).toHttpUrl().queryParameter(name)

    @Test
    fun `logs in once, sends the session cookie and lists clients from inbound settings`() = runBlocking {
        loginOk()
        respond(inbounds)
        val clients = api().listClients()

        val login = server.takeRequest()
        assertEquals("/v2path/login", login.url.encodedPath)
        assertEquals("admin", login.form("username"))
        assertEquals("s3cret", login.form("password"))
        val list = server.takeRequest()
        assertEquals("/v2path/panel/api/inbounds/list", list.url.encodedPath)
        assertEquals("3x-ui=session-1", list.headers["Cookie"])
        assertEquals("XMLHttpRequest", list.headers["X-Requested-With"])

        assertEquals(listOf("anna", "bob"), clients.map { it.email })
        assertEquals(300L, clients[0].up + clients[0].down)
        assertEquals(listOf(3), clients[0].inboundIds)
        assertFalse(clients[1].enabled)
        assertEquals(1073741824L, clients[1].totalBytes)
    }

    @Test
    fun `an expired session is re-established once`() = runBlocking {
        loginOk()
        respond("""{"success":false,"msg":"login again"}""", code = 401)
        loginOk()
        respond("""{"success":true,"obj":["anna"]}""")
        assertEquals(setOf("anna"), api().onlineEmails())
        assertEquals(
            listOf("/v2path/login", "/v2path/panel/api/inbounds/onlines", "/v2path/login", "/v2path/panel/api/inbounds/onlines"),
            List(4) { server.takeRequest().url.encodedPath },
        )
    }

    @Test
    fun `wrong credentials are reported as unauthorized`() = runBlocking {
        respond("""{"success":false,"msg":"Wrong username or password"}""")
        try {
            api().listInbounds()
            fail("expected unauthorized")
        } catch (e: AppException) {
            assertEquals(AppError.PanelUnauthorized, e.error)
        }
    }

    @Test
    fun `new vless client gets a generated uuid and vision flow`() = runBlocking {
        loginOk()
        respond(inbounds)
        respond("""{"success":true,"msg":"Client(s) added"}""")
        api().addClient(NewClient("carol", "sub3", 0, 0, "xtls-rprx-vision", listOf(3)))
        server.takeRequest()
        server.takeRequest()
        val add = server.takeRequest()
        assertEquals("/v2path/panel/api/inbounds/addClient", add.url.encodedPath)
        assertEquals("3", add.form("id"))
        val client = json.parseToJsonElement(add.form("settings")!!).jsonObject["clients"]!!.jsonArray.single().jsonObject
        UUID.fromString(client["id"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", client["flow"]!!.jsonPrimitive.content)
        assertEquals("carol", client["email"]!!.jsonPrimitive.content)
        assertEquals("sub3", client["subId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `new shadowsocks 2022 client gets a 32 byte key and no flow`() = runBlocking {
        loginOk()
        respond(inbounds)
        respond("""{"success":true}""")
        api().addClient(NewClient("dave", "sub4", 0, 0, "xtls-rprx-vision", listOf(5)))
        server.takeRequest()
        server.takeRequest()
        val client = json.parseToJsonElement(server.takeRequest().form("settings")!!).jsonObject["clients"]!!.jsonArray.single().jsonObject
        assertEquals(32, java.util.Base64.getDecoder().decode(client["password"]!!.jsonPrimitive.content).size)
        assertEquals("", client["method"]!!.jsonPrimitive.content)
        assertEquals("", client["flow"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a client can only go to one inbound`() = runBlocking {
        try {
            api().addClient(NewClient("x", "sub5", 0, 0, "", listOf(3, 4)))
            fail("expected error")
        } catch (e: AppException) {
            assertTrue(e.error is AppError.PanelError)
        }
    }

    @Test
    fun `trojan client is addressed by its password and unknown fields survive edits`() = runBlocking {
        loginOk()
        respond(inbounds)
        respond("""{"success":true,"msg":"Client updated"}""")
        respond(inbounds)
        respond("""{"success":true}""")
        val api = api()
        api.updateClient("bob", ClientChanges("bob2", "sub2", 5, 6, enabled = true, inboundIds = listOf(4)))
        api.updateClient("anna", ClientChanges("anna", "sub1", 0, 0, enabled = false, inboundIds = listOf(3)))
        server.takeRequest()
        server.takeRequest()
        val trojan = server.takeRequest()
        assertEquals(listOf("panel", "api", "inbounds", "updateClient", "p@ss/1"), trojan.url.pathSegments.drop(1))
        assertEquals("4", trojan.form("id"))
        val bob = json.parseToJsonElement(trojan.form("settings")!!).jsonObject["clients"]!!.jsonArray.single().jsonObject
        assertEquals("bob2", bob["email"]!!.jsonPrimitive.content)
        assertEquals("p@ss/1", bob["password"]!!.jsonPrimitive.content)
        server.takeRequest()
        val vless = server.takeRequest()
        assertEquals("11111111-1111-1111-1111-111111111111", vless.url.pathSegments.last())
        val anna = json.parseToJsonElement(vless.form("settings")!!).jsonObject["clients"]!!.jsonArray.single().jsonObject
        assertEquals("keep", anna["custom"]!!.jsonPrimitive.content)
        assertEquals("false", anna["enable"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete and reset address the client's inbound`() = runBlocking {
        loginOk()
        respond(inbounds)
        respond("""{"success":true}""")
        respond(inbounds)
        respond("""{"success":true}""")
        val api = api()
        api.deleteClient("anna")
        api.resetClientTraffic("bob")
        server.takeRequest()
        server.takeRequest()
        assertEquals("/v2path/panel/api/inbounds/3/delClient/11111111-1111-1111-1111-111111111111", server.takeRequest().url.encodedPath)
        server.takeRequest()
        assertEquals("/v2path/panel/api/inbounds/4/resetClientTraffic/bob", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `server routes follow the panel version`() = runBlocking {
        val status = """{"success":true,"obj":{"cpu":1.5,"mem":{"current":1,"total":2},"uptime":60,"loads":[0.1],"xray":{"state":"running","errorMsg":"","version":"1.8.24"}}}"""
        loginOk()
        respond(status)
        respond("""{"success":true}""")
        val legacy = api("2.6.7")
        assertEquals("v2.6.7", legacy.status().panelVersion)
        legacy.restartXray()
        server.takeRequest()
        server.takeRequest().let { assertEquals("POST" to "/v2path/server/status", it.method to it.url.encodedPath) }
        assertEquals("/v2path/server/restartXrayService", server.takeRequest().url.encodedPath)

        loginOk()
        respond(status)
        api("2.9.4").status()
        server.takeRequest()
        server.takeRequest().let { assertEquals("GET" to "/v2path/panel/api/server/status", it.method to it.url.encodedPath) }
    }

    @Test
    fun `inbound summary carries enable flag, traffic, clients and reality details`() = runBlocking {
        loginOk()
        respond(
            """{"success":true,"obj":[{"id":7,"remark":"r","port":443,"protocol":"vless","enable":false,"up":5,"down":6,
               "settings":"{\"clients\":[{\"email\":\"a\"},{\"email\":\"b\"}]}",
               "streamSettings":"{\"network\":\"tcp\",\"security\":\"reality\",\"realitySettings\":{\"dest\":\"www.apple.com:443\",\"serverNames\":[\"www.apple.com\"],\"settings\":{\"fingerprint\":\"safari\"}}}"}]}""",
        )
        val inbound = api().listInbounds().single()
        assertFalse(inbound.enabled)
        assertEquals(11L, inbound.up + inbound.down)
        assertEquals(2, inbound.clientCount)
        assertEquals("www.apple.com:443", inbound.reality!!.target)
        assertEquals(listOf("www.apple.com"), inbound.reality!!.serverNames)
        assertEquals("safari", inbound.reality!!.fingerprint)
    }

    @Test
    fun `disabling an inbound posts the whole inbound back with settings as text`() = runBlocking {
        loginOk()
        respond(
            """{"success":true,"obj":{"id":3,"remark":"main","port":443,"protocol":"vless","enable":true,"up":1,"down":2,
               "settings":"{\"clients\":[{\"email\":\"anna\"}]}","streamSettings":"{\"network\":\"tcp\"}",
               "sniffing":"{\"enabled\":true}","clientStats":[{"email":"anna"}]}}""",
        )
        respond("""{"success":true}""")
        api().setInboundEnabled(3, false)

        server.takeRequest()
        assertEquals("/v2path/panel/api/inbounds/get/3", server.takeRequest().url.encodedPath)
        val update = server.takeRequest()
        assertEquals("/v2path/panel/api/inbounds/update/3", update.url.encodedPath)
        assertEquals("false", update.form("enable"))
        assertEquals("main", update.form("remark"))
        assertEquals("{\"clients\":[{\"email\":\"anna\"}]}", update.form("settings"))
        assertEquals(null, update.form("clientStats"))
        assertEquals(null, update.form("id"))
    }

    @Test
    fun `deleting an inbound`() = runBlocking {
        loginOk()
        respond("""{"success":true}""")
        api().deleteInbound(4)
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("POST", delete.method)
        assertEquals("/v2path/panel/api/inbounds/del/4", delete.url.encodedPath)
    }
}
