package dev.autorasqr.xuimanager.ui.panel

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.PanelWebSession
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.message
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(
    onBack: () -> Unit,
    viewModel: PanelViewModel = hiltViewModel(),
) {
    // The panel shows client UUIDs, keys and its own settings: keep it out of screenshots and Recents.
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var certificateRejected by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? PanelUiState.Ready)?.session?.serverName ?: stringResource(R.string.panel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        pageError = null
                        if (webView != null && state is PanelUiState.Ready) webView?.reload() else viewModel.load()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                PanelUiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.panel_signing_in), modifier = Modifier.padding(top = 16.dp))
                }
                is PanelUiState.Failed -> Message(current.error.message(), onRetry = viewModel::load)
                is PanelUiState.Ready -> {
                    Column(Modifier.fillMaxSize()) {
                        current.session.loginError?.let { error ->
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.panel_autologin_failed, error.message()),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        PanelWebView(
                            session = current.session,
                            onCreated = { webView = it },
                            onHistoryChanged = { canGoBack = it },
                            onLoading = { loading = it },
                            onPageError = { pageError = it },
                            onCertificateRejected = { certificateRejected = true },
                        )
                    }
                    when {
                        certificateRejected -> Message(stringResource(R.string.error_cert_invalid), onRetry = null)
                        pageError != null -> Message(stringResource(R.string.panel_page_error, pageError.orEmpty())) {
                            pageError = null
                            webView?.reload()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled") // The panel is a single-page app; JS runs only on the pinned panel origin.
@Composable
private fun PanelWebView(
    session: PanelWebSession,
    onCreated: (WebView) -> Unit,
    onHistoryChanged: (Boolean) -> Unit,
    onLoading: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    onCertificateRejected: () -> Unit,
) {
    val context = LocalContext.current
    val origin = remember(session) { session.origin.toHttpUrlOrNull() }

    fun isPanelUrl(url: String?): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        return origin != null && parsed.scheme == origin.scheme && parsed.host == origin.host && parsed.port == origin.port
    }

    val webView = remember(session) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                builtInZoomControls = false
            }
            // Safe Browsing would send hashes of URLs that contain the panel's secret path to a third party.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
            }
            webViewClient = object : WebViewClient() {
                // The panel has a publicly trusted certificate; any TLS error is refused, never overridden.
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    onCertificateRejected()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (isPanelUrl(url)) return false
                    // Links leaving the panel (docs, GitHub) open in the user's browser, never inside the session.
                    if (request.url.scheme == "https" || request.url.scheme == "http") {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = onLoading(true)

                override fun onPageFinished(view: WebView, url: String?) = onLoading(false)

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
                    onHistoryChanged(view.canGoBack())

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) onPageError(error.description?.toString().orEmpty())
                }
            }
        }
    }

    DisposableEffect(webView) {
        onCreated(webView)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
        var disposed = false
        // Start from a clean jar, put only this panel's session cookie in it, then load.
        cookies.removeAllCookies {
            if (disposed) return@removeAllCookies
            session.cookies.forEach { cookies.setCookie(session.origin, it) }
            cookies.flush()
            webView.loadUrl(session.startUrl)
        }
        onDispose {
            disposed = true
            // Leave no session behind: cookies, web storage and cache go with the screen.
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            webView.destroy()
            cookies.removeAllCookies(null)
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}
