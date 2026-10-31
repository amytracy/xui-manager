package dev.autorasqr.xuimanager.data.geo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Country flag emoji at the start of the inbound names the app creates. */
object Flags {
    /** Shown when the country can't be detected. */
    const val EU = "🇪🇺"

    private const val FIRST = 0x1F1E6
    private const val LAST = 0x1F1FF

    /** Flag of an ISO 3166-1 alpha-2 code ("DE" → 🇩🇪); [EU] for anything else. */
    fun emoji(code: String?): String {
        val c = code?.trim()?.uppercase().orEmpty()
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return EU
        return buildString { c.forEach { appendCodePoint(FIRST + (it - 'A')) } }
    }

    private fun isIndicator(cp: Int) = cp in FIRST..LAST

    fun startsWithFlag(remark: String): Boolean {
        if (remark.isEmpty()) return false
        val first = remark.codePointAt(0)
        if (!isIndicator(first)) return false
        val second = Character.charCount(first)
        return second < remark.length && isIndicator(remark.codePointAt(second))
    }

    /** [remark] without a leading flag and the space after it, for matching names the app gave. */
    fun strip(remark: String): String {
        if (!startsWithFlag(remark)) return remark
        val first = remark.codePointAt(0)
        val end = Character.charCount(first) + Character.charCount(remark.codePointAt(Character.charCount(first)))
        return remark.substring(end).removePrefix(" ")
    }

    /** [remark] with [flag] in front, unless it already starts with a flag. */
    fun prefix(remark: String, flag: String): String = when {
        startsWithFlag(remark) -> remark
        remark.isBlank() -> flag
        else -> "$flag $remark"
    }
}

/** Country of a server by its address. */
interface CountryLookup {
    /** ISO alpha-2 code, or null when it can't be found out. Never throws (except cancellation). */
    suspend fun countryCode(host: String): String?

    /** [Flags.emoji] of [host]'s country, [Flags.EU] when unknown. */
    suspend fun flag(host: String): String = Flags.emoji(countryCode(host))
}

/**
 * Asks public geo-IP services from the phone (no keys): api.country.is first, ipwho.is as a fallback. A domain is
 * resolved to its IP first. Found countries are kept for the app run.
 */
@Singleton
class HttpCountryLookup internal constructor(
    private val client: OkHttpClient,
    private val primaryBase: String,
    private val fallbackBase: String,
    private val resolve: (String) -> String?,
) : CountryLookup {

    @Inject
    constructor() : this(defaultClient(), "https://api.country.is/", "https://ipwho.is/", ::resolveIp)

    private val cache = ConcurrentHashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun countryCode(host: String): String? = withContext(Dispatchers.IO) {
        val key = host.trim().lowercase()
        if (key.isEmpty()) return@withContext null
        cache[key]?.let { return@withContext it }
        try {
            val ip = resolve(key) ?: return@withContext null
            val code = query(primaryBase + ip, "country") ?: query(fallbackBase + ip, "country_code")
            code?.also { cache[key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun query(url: String, field: String): String? = try {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = json.parseToJsonElement(response.body.string()).jsonObject
            if (body.flag("success") == false) return null
            body[field]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.flag(name: String): Boolean? = runCatching { this[name]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        /** IPv4 preferred: geo-IP data for it is the most complete. */
        private fun resolveIp(host: String): String? {
            val all = InetAddress.getAllByName(host.removePrefix("[").removeSuffix("]"))
            return (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
        }
    }
}
