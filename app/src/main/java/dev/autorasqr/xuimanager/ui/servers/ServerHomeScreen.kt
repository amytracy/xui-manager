package dev.autorasqr.xuimanager.ui.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.autorasqr.xuimanager.domain.model.UiStyle
import dev.autorasqr.xuimanager.ui.navigation.ServerDetailRoute
import dev.autorasqr.xuimanager.ui.theme.LocalUiStyle

/**
 * The server screen: the overview plus inbounds, clients, nodes and the rest as bottom tabs. Each tab is its own
 * destination (own state and ViewModel, kept while switching); [tabs] declares them, everything else opens full screen
 * over the tabs.
 */
@Composable
fun ServerHomeScreen(
    tabs: NavGraphBuilder.() -> Unit,
    viewModel: ServerHomeViewModel = hiltViewModel(),
) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    val available = ServerTab.tabsFor(server)
    val tabNav = rememberNavController()
    val current by tabNav.currentBackStackEntryAsState()
    val selected = available.firstOrNull { tab -> current?.destination?.hierarchy?.any { it.hasRoute(tab.route) } == true }

    Scaffold(
        // Each tab's own Scaffold handles the status bar; this one only makes room for the tab bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (available.isNotEmpty()) {
                ServerTabBar(available, selected) { tab ->
                    tabNav.navigate(tab.routeFor(viewModel.serverId)) {
                        popUpTo(tabNav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = tabNav,
            startDestination = ServerDetailRoute(viewModel.serverId),
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            builder = tabs,
        )
    }
}

@Composable
private fun ServerTabBar(tabs: List<ServerTab>, selected: ServerTab?, onSelect: (ServerTab) -> Unit) {
    val oneUi = LocalUiStyle.current == UiStyle.ONE_UI
    Column {
        // One UI: a flat bar on the page's own ground with a hairline, no pill behind the selected icon.
        if (oneUi) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = if (oneUi) MaterialTheme.colorScheme.background else NavigationBarDefaults.containerColor) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = {
                        Text(stringResource(tab.label), fontWeight = if (oneUi && isSelected) FontWeight.Bold else null, maxLines = 1)
                    },
                    colors = if (oneUi) {
                        NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        NavigationBarItemDefaults.colors()
                    },
                )
            }
        }
    }
}

private val ServerTab.icon: ImageVector
    get() = when (this) {
        ServerTab.OVERVIEW -> Icons.Default.Home
        ServerTab.INBOUNDS -> Icons.AutoMirrored.Filled.List
        ServerTab.CLIENTS -> Icons.Default.Person
        ServerTab.NODES -> Icons.Default.Share
        ServerTab.MORE -> Icons.Default.Menu
    }
