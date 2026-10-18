package dev.autorasqr.xuimanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5A96),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF545F71),
    tertiary = Color(0xFF2E6B4F),
    background = Color(0xFFF8F9FB),
    surface = Color(0xFFF8F9FB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2C9FF),
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF00487F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBCC7DB),
    tertiary = Color(0xFF94D5B2),
    background = Color(0xFF111418),
    surface = Color(0xFF111418),
)

/** Status colours that are not part of the Material scheme. */
@Immutable
data class StatusColors(val online: Color, val warning: Color, val success: Color)

private val StatusLight = StatusColors(online = Color(0xFF2E9D5B), warning = Color(0xFF9A5B00), success = Color(0xFF2E9D5B))
private val StatusDark = StatusColors(online = Color(0xFF6FD99A), warning = Color(0xFFFFB95C), success = Color(0xFF6FD99A))

val LocalStatusColors = staticCompositionLocalOf { StatusLight }

@Composable
fun XuiManagerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) StatusDark else StatusLight) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
