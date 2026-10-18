package dev.autorasqr.xuimanager.data.panel

import dev.autorasqr.xuimanager.data.panel.net.MemoryCookieJar
import dev.autorasqr.xuimanager.data.panel.net.PanelHttpClientFactory
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Browser-style sign-in used to hand a session cookie to the WebView. */
class PanelWebLoginTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer()
    private val cookies = MemoryCookieJar()

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val base get() = "https://localhost:${server.port}/p4th/".toHttpUrl()
    private val trusting = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
    private val client get() = PanelHttpClientFactory(trusting.sslSocketFactory() to trusting.trustManager).create(null, cookies)

    private fun respond(body: String, cookie: String? = null) {
        val builder = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body)
        cookie?.let { builder.addHeader("Set-Cookie", it) }
        server.enqueue(builder.build())
    }

    @Test
    fun `v3 fetches a CSRF token first and sends it with a JSON login`() {
        respond("""{"success":true,"obj":"csrf-abc"}""", cookie = "3x-ui=pre-login; Path=/p4th/; Secure; HttpOnly")
        respond("""{"success":true,"msg":"Logged in"}""", cookie = "3x-ui=logged-in; Path=/p4th/; Secure; HttpOnly")
        PanelWebLogin.login(client, base, "v3.10.2", "admin", "pa ss", json)

        val csrf = server.takeRequest()
        assertEquals("GET", csrf.method)
        assertEquals("/p4th/csrf-token", csrf.url.encodedPath)
        val login = server.takeRequest()
        assertEquals("/p4th/login", login.url.encodedPath)
        assertEquals("csrf-abc", login.headers["X-CSRF-Token"])
        assertEquals("3x-ui=pre-login", login.headers["Cookie"])
        val body = json.parseToJsonElement(login.body!!.utf8()).jsonObject
        assertEquals("pa ss", body["password"]!!.jsonPrimitive.content)
        assertFalse("the password must never be in the URL", login.url.toString().contains("pa"))

        val session = cookies.loadForRequest(base.resolve("panel/")!!).single()
        assertEquals("logged-in", session.value)
        assertEquals(true, session.secure)
        assertEquals(true, session.httpOnly)
    }

    @Test
    fun `v2 posts a form`() {
        respond("""{"success":true}""", cookie = "3x-ui=s2; Path=/; HttpOnly")
        PanelWebLogin.login(client, base, "2.6.7", "admin", "secret", json)
        val login = server.takeRequest()
        assertEquals("/p4th/login", login.url.encodedPath)
        assertEquals("username=admin&password=secret", login.body!!.utf8())
        assertEquals("s2", cookies.loadForRequest(base).single().value)
    }

    @Test
    fun `rejected credentials are reported as unauthorized`() {
        respond("""{"success":true,"obj":"t"}""")
        respond("""{"success":false,"msg":"Wrong username or password"}""")
        try {
            PanelWebLogin.login(client, base, "3.10.2", "admin", "bad", json)
            fail("expected failure")
        } catch (e: AppException) {
            assertEquals(AppError.PanelUnauthorized, e.error)
        }
    }
}
