package dev.autorasqr.xuimanager.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.navigation.MoreRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    serverRepository: ServerRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<MoreRoute>().serverId

    val subscriptionSettings: StateFlow<Boolean> = serverRepository.observeServer(serverId)
        .map { ServerTab.isV3(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/** The "More" tab: the server's less frequent screens and the app's own settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onRouting: (Long) -> Unit,
    onSubscription: (Long) -> Unit,
    onOpenPanel: (Long) -> Unit,
    onServerSettings: (Long) -> Unit,
    onAppearance: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val subscriptionSettings by viewModel.subscriptionSettings.collectAsStateWithLifecycle()
    val id = viewModel.serverId
    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = { AppTopBar(title = { Text(stringResource(R.string.tab_more)) }, scrollBehavior = appBarScroll) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MenuGroup(
                buildList {
                    add(MenuEntry(stringResource(R.string.action_routing), stringResource(R.string.more_routing_text)) { onRouting(id) })
                    if (subscriptionSettings) {
                        add(MenuEntry(stringResource(R.string.action_subscription), stringResource(R.string.more_subscription_text)) { onSubscription(id) })
                    }
                    add(MenuEntry(stringResource(R.string.action_open_panel), stringResource(R.string.more_open_panel_text)) { onOpenPanel(id) })
                },
            )
            MenuGroup(
                listOf(
                    MenuEntry(stringResource(R.string.server_settings), stringResource(R.string.more_server_settings_text)) { onServerSettings(id) },
                    MenuEntry(stringResource(R.string.appearance_title), stringResource(R.string.more_appearance_text), onAppearance),
                ),
            )
        }
    }
}

private class MenuEntry(val title: String, val text: String, val onClick: () -> Unit)

@Composable
private fun MenuGroup(entries: List<MenuEntry>) {
    Card(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListItem(
                headlineContent = { Text(entry.title) },
                supportingContent = { Text(entry.text) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = entry.onClick),
            )
        }
    }
}
