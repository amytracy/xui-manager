package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.BuildConfig
import dev.autorasqr.xuimanager.data.app.AppReleases
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.AppUpdateOffer
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.repository.AppUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks the app's GitHub releases straight from the phone (system CAs, no token: GitHub allows 60 such requests an
 * hour per IP, plenty for a check per app run). Installing stays manual: the user opens the release page.
 */
@Singleton
class AppUpdateRepositoryImpl internal constructor(
    private val json: Json,
    private val client: OkHttpClient,
    private val latestUrl: String,
    private val currentVersion: String,
) : AppUpdateRepository {

    @Inject
    constructor(json: Json) : this(json, defaultClient(), AppReleases.LATEST_URL, BuildConfig.VERSION_NAME)

    @Volatile private var cached: UpdateCheck<AppUpdateOffer>? = null
    private val prompted = AtomicBoolean(false)

    override suspend fun check(refresh: Boolean): UpdateCheck<AppUpdateOffer> = withContext(Dispatchers.IO) {
        if (!refresh) cached?.let { return@withContext it }
        val result = try {
            val release = latest()
            offerFrom(currentVersion, release)?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext UpdateCheck.Unavailable(e.toAppError())
        }
        cached = result
        result
    }

    override fun shouldPrompt(): Boolean = !prompted.get()

    override fun markPrompted() {
        prompted.set(true)
    }

    private fun latest(): GitHubRelease {
        val request = Request.Builder()
            .url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "XUI-Manager/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                // 404: nothing published yet; 403/429: rate limit. GitHub explains itself in "message".
                val message = runCatching { json.decodeFromString<GitHubError>(text).message }.getOrNull().orEmpty()
                throw AppException(AppError.ReleaseCheckFailed(listOf("HTTP ${response.code}", message).filter { it.isNotBlank() }.joinToString(": ")))
            }
            return try {
                json.decodeFromString<GitHubRelease>(text)
            } catch (e: SerializationException) {
                throw AppException(AppError.ReleaseCheckFailed("unexpected response"), e)
            } catch (e: IllegalArgumentException) {
                throw AppException(AppError.ReleaseCheckFailed("unexpected response"), e)
            }
        }
    }

    @Serializable
    internal data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
    )

    @Serializable
    private data class GitHubError(val message: String = "")

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * An offer when [release] is newer than [currentVersion] ("0.10.0", or "0.10.0-e2e" for the stand build).
         * A tag that does not parse as a version is ignored rather than offered.
         */
        internal fun offerFrom(currentVersion: String, release: GitHubRelease): AppUpdateOffer? {
            val cmp = PanelVersion.compare(currentVersion, release.tagName) ?: return null
            if (cmp >= 0) return null
            return AppUpdateOffer(
                currentVersion = currentVersion,
                latestVersion = PanelVersion.normalize(release.tagName),
                releaseUrl = release.htmlUrl.ifBlank { AppReleases.RELEASES_PAGE },
                notes = release.body.orEmpty().trim(),
            )
        }
    }
}
