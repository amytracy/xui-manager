package dev.autorasqr.xuimanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.autorasqr.xuimanager.data.app.AppearanceStore
import dev.autorasqr.xuimanager.data.transfer.TransferCodec
import dev.autorasqr.xuimanager.ui.navigation.AppNavHost
import dev.autorasqr.xuimanager.ui.theme.XuiManagerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appearance: AppearanceStore

    /** A transfer link the app was opened with, until the navigation has shown it. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingLink.value = transferLink(intent)
        setContent {
            val style by appearance.style.collectAsStateWithLifecycle()
            val link by pendingLink.collectAsStateWithLifecycle()
            XuiManagerTheme(style) {
                AppNavHost(pendingLink = link, onLinkHandled = { pendingLink.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        transferLink(intent)?.let { pendingLink.value = it }
    }

    private fun transferLink(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf(TransferCodec::isLink)
}
