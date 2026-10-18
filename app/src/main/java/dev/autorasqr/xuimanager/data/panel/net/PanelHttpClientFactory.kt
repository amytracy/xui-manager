package dev.autorasqr.xuimanager.data.panel.net

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS clients for the panel. Certificates are checked by Android's system trust store with normal hostname
 * verification: the panel serves a Let's Encrypt certificate for its IP or domain, nothing is pinned or exempted.
 */
@Singleton
class PanelHttpClientFactory internal constructor(
    /** Only tests replace the trust store; the app always uses the system one. */
    private val testTrust: Pair<SSLSocketFactory, X509TrustManager>?,
) {
    @Inject constructor() : this(null)

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .apply { testTrust?.let { (factory, manager) -> sslSocketFactory(factory, manager) } }
            .build()
    }

    /**
     * No request logging, by design. v3 authenticates with [bearerToken]; v2 uses a session [cookieJar] and marks
     * requests as AJAX so an expired session answers 401 instead of redirecting to the login page.
     */
    fun create(bearerToken: String?, cookieJar: CookieJar? = null): OkHttpClient =
        base.newBuilder()
            .apply { if (bearerToken != null) addInterceptor(bearerInterceptor(bearerToken)) }
            .apply {
                if (cookieJar != null) {
                    cookieJar(cookieJar)
                    addInterceptor(ajaxInterceptor)
                }
            }
            .build()

    private val ajaxInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .build(),
        )
    }

    private fun bearerInterceptor(token: String) = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build(),
        )
    }
}
