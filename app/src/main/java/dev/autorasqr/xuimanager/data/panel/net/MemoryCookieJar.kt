package dev.autorasqr.xuimanager.data.panel.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Session cookies for one panel, kept only in memory and dropped with the adapter. */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { fresh ->
            this.cookies.removeAll { it.name == fresh.name && it.domain == fresh.domain && it.path == fresh.path }
            if (fresh.expiresAt > System.currentTimeMillis()) this.cookies += fresh
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() = cookies.clear()
}
