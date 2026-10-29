package dev.autorasqr.xuimanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.domain.model.UiStyle

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

// One UI: a flat grey (light) or black (dark) ground, white / near-black blocks on it, Samsung's blue accent. Bars use
// the ground colour (surfaceContainer), cards the block colour (surfaceContainerHighest), dialogs a raised one.
private val OneUiLight = lightColorScheme(
    primary = Color(0xFF0A7CF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF00398A),
    secondary = Color(0xFF5D6470),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F2),
    onSecondaryContainer = Color(0xFF1A1C1F),
    tertiary = Color(0xFF1B8A5A),
    background = Color(0xFFF4F4F4),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFF4F4F4),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFFCFCFC),
    outline = Color(0xFF8E8E8E),
    outlineVariant = Color(0xFFE2E2E2),
    error = Color(0xFFE0342C),
)

private val OneUiDark = darkColorScheme(
    primary = Color(0xFF3E91FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF13325A),
    onPrimaryContainer = Color(0xFFA9CCFF),
    secondary = Color(0xFFAEB6C2),
    onSecondary = Color(0xFF1A1C1F),
    secondaryContainer = Color(0xFF1F2733),
    onSecondaryContainer = Color(0xFFD6E3F7),
    tertiary = Color(0xFF6FD99A),
    background = Color.Black,
    onBackground = Color(0xFFF5F5F5),
    surface = Color.Black,
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF181818),
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF6159),
)

private val MaterialShapes = Shapes()
private val MaterialTypography = Typography()

private val OneUiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val OneUiTypography = Typography().run {
    copy(
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Status colours that are not part of the Material scheme. */
@Immutable
data class StatusColors(val online: Color, val warning: Color, val success: Color)

private val StatusLight = StatusColors(online = Color(0xFF2E9D5B), warning = Color(0xFF9A5B00), success = Color(0xFF2E9D5B))
private val StatusDark = StatusColors(online = Color(0xFF6FD99A), warning = Color(0xFFFFB95C), success = Color(0xFF6FD99A))

val LocalStatusColors = staticCompositionLocalOf { StatusLight }

/** The active [UiStyle], for the few components that differ in shape and not only in colour (top bars, tab bar). */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.DEFAULT }

@Composable
fun XuiManagerTheme(style: UiStyle = UiStyle.DEFAULT, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        style == UiStyle.ONE_UI -> if (dark) OneUiDark else OneUiLight
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) StatusDark else StatusLight,
        LocalUiStyle provides style,
    ) {
        // One call for both styles: separate branches would recompose the content from scratch on a switch, losing the
        // navigation stack.
        val oneUi = style == UiStyle.ONE_UI
        MaterialTheme(
            colorScheme = colors,
            shapes = if (oneUi) OneUiShapes else MaterialShapes,
            typography = if (oneUi) OneUiTypography else MaterialTypography,
            content = content,
        )
    }
}
