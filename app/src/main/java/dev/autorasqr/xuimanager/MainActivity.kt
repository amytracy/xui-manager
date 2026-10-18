package dev.autorasqr.xuimanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.autorasqr.xuimanager.ui.navigation.AppNavHost
import dev.autorasqr.xuimanager.ui.theme.XuiManagerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            XuiManagerTheme {
                AppNavHost()
            }
        }
    }
}
