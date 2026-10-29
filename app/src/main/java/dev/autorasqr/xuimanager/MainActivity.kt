package dev.autorasqr.xuimanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.autorasqr.xuimanager.data.app.AppearanceStore
import dev.autorasqr.xuimanager.ui.navigation.AppNavHost
import dev.autorasqr.xuimanager.ui.theme.XuiManagerTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appearance: AppearanceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val style by appearance.style.collectAsStateWithLifecycle()
            XuiManagerTheme(style) {
                AppNavHost()
            }
        }
    }
}
