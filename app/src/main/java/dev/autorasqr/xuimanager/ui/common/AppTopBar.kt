package dev.autorasqr.xuimanager.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.domain.model.UiStyle
import dev.autorasqr.xuimanager.ui.theme.LocalUiStyle

/** Pass to [AppTopBar] and hook into the screen's Scaffold with `Modifier.nestedScroll(it.nestedScrollConnection)`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appBarScrollBehavior(): TopAppBarScrollBehavior =
    if (LocalUiStyle.current == UiStyle.ONE_UI) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }

/**
 * A screen's top bar in the current style: Material's compact bar, or One UI's large centred title that takes the top
 * of the screen and folds into a compact bar as the content scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /** False keeps the compact bar in One UI as well, for content that never scrolls it away (a web page). */
    large: Boolean = true,
) {
    if (large && LocalUiStyle.current == UiStyle.ONE_UI) {
        LargeTopAppBar(
            // Centred like One UI's header; the same slot is the folded bar's title, centred between its icons.
            title = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { title() } },
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = ONE_UI_HEADER_HEIGHT,
            scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions, scrollBehavior = scrollBehavior)
    }
}

private val ONE_UI_HEADER_HEIGHT = 196.dp
