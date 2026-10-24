package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppUpdateOffer
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; isLenient = true }
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun repository(current: String) =
        AppUpdateRepositoryImpl(json, OkHttpClient(), server.url("/repos/amytracy/xui-manager/releases/latest").toString(), current)

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    private val release = """{"tag_name":"v0.11.0","name":"0.11.0","html_url":"https://github.com/amytracy/xui-manager/releases/tag/v0.11.0",
        "prerelease":false,"body":"Update checks\r\n- Xray","assets":[{"name":"xui-manager-0.11.0-release.apk"}]}"""

    @Test
    fun `a newer release is offered with its page and notes`() = runBlocking {
        respond(release)
        val result = repository("0.10.0").check()
        assertEquals(
            UpdateCheck.Available(
                AppUpdateOffer("0.10.0", "v0.11.0", "https://github.com/amytracy/xui-manager/releases/tag/v0.11.0", "Update checks\r\n- Xray"),
            ),
            result,
        )
        val request = server.takeRequest()
        assertEquals("/repos/amytracy/xui-manager/releases/latest", request.url.encodedPath)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("XUI-Manager/0.10.0", request.headers["User-Agent"])
    }

    @Test
    fun `the same or an older release is up to date, the stand build suffix aside`() = runBlocking {
        respond(release)
        assertEquals(UpdateCheck.UpToDate, repository("0.11.0").check())
        respond(release)
        assertEquals(UpdateCheck.UpToDate, repository("0.12.0").check())
        respond(release)
        assertEquals(UpdateCheck.UpToDate, repository("0.11.0-e2e").check())
    }

    @Test
    fun `the result is remembered until a refresh`() = runBlocking {
        respond(release)
        val repository = repository("0.11.0")
        repository.check()
        assertEquals(UpdateCheck.UpToDate, repository.check())
        assertEquals(1, server.requestCount)
        respond(release)
        repository.check(refresh = true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a rate limit is reported, not offered, and checked again next time`() = runBlocking {
        respond("""{"message":"API rate limit exceeded for 203.0.113.7."}""", code = 403)
        val repository = repository("0.10.0")
        val result = repository.check()
        assertEquals(UpdateCheck.Unavailable(AppError.ReleaseCheckFailed("HTTP 403: API rate limit exceeded for 203.0.113.7.")), result)
        respond(release)
        assertTrue(repository.check() is UpdateCheck.Available)
    }

    @Test
    fun `a tag that is not a version offers nothing`() {
        val offer = AppUpdateRepositoryImpl.offerFrom("0.10.0", AppUpdateRepositoryImpl.GitHubRelease("nightly", "https://x", null))
        assertEquals(null, offer)
    }
}
