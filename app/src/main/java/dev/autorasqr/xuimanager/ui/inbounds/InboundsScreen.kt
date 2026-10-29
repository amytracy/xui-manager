package dev.autorasqr.xuimanager.ui.inbounds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.InboundItem
import dev.autorasqr.xuimanager.domain.model.SelfStealAction
import dev.autorasqr.xuimanager.domain.model.SelfStealProgress
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.FingerprintField
import dev.autorasqr.xuimanager.ui.common.Formatters
import dev.autorasqr.xuimanager.ui.common.SitePageChoice
import dev.autorasqr.xuimanager.ui.common.SitePageField
import dev.autorasqr.xuimanager.ui.common.SniField
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboundsScreen(
    /** Null when the screen is a tab of the server screen. */
    onBack: (() -> Unit)?,
    /** Opens the full editor; null creates a new inbound. */
    onEditor: (Int?) -> Unit,
    viewModel: InboundsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    // Back from the full editor: show what it saved.
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) viewModel.refresh()
        resumedOnce = true
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbar.showSnackbar(resources.getString(message))
    }

    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(stringResource(R.string.inbounds_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.snapshot != null) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openAddMenu,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.inbound_add)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.snapshot != null,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val snapshot = state.snapshot
            when {
                snapshot == null && state.error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                snapshot == null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!.message(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.selfSteal?.let { progress ->
                        item(key = "selfsteal") { SelfStealProgressCard(progress, onDismiss = viewModel::dismissSelfStealProgress) }
                    }
                    // The app may not know the site's port (adopted panel, older app version), but a self-steal inbound
                    // means the site is there; the script refuses a server without one anyway.
                    val siteInstalled = snapshot.selfStealPort != null || snapshot.items.any { it.selfSteal && it.nodeId == null }
                    // Self-steal inbounds change the page from their own card; this card is only the fallback for a
                    // site kept after its last inbound was deleted.
                    val pageOnCard = snapshot.items.any { it.selfSteal && it.siteServerId == viewModel.serverId }
                    if (siteInstalled && snapshot.domain != null && !pageOnCard) {
                        item(key = "decoy") {
                            DecoySiteCard(snapshot.domain, enabled = !viewModel.selfStealRunning, onChangePage = { viewModel.openSitePage() })
                        }
                    }
                    state.error?.let { error ->
                        item(key = "error") { Text(error.message(), color = MaterialTheme.colorScheme.error) }
                    }
                    if (snapshot.items.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.inbounds_empty),
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(snapshot.items, key = { it.id }) { item ->
                        InboundCard(
                            item = item,
                            busy = item.id in state.busyIds,
                            onToggle = { viewModel.setEnabled(item, it) },
                            onEdit = { viewModel.openEditReality(item) },
                            onAdvanced = { onEditor(item.id) },
                            onDelete = { viewModel.requestDelete(item) },
                            onSitePage = if (item.selfSteal && item.siteDomain != null && !viewModel.selfStealRunning) {
                                { viewModel.openSitePage(item) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    if (state.addMenu) {
        AddInboundDialog(
            hasDomain = state.snapshot?.domain != null,
            selfStealRunning = viewModel.selfStealRunning,
            onSelfSteal = viewModel::openSelfSteal,
            onAdvanced = { viewModel.closeAddMenu(); onEditor(null) },
            onDismiss = viewModel::closeAddMenu,
        )
    }
    state.realityForm?.let { form ->
        RealityFormDialog(form, canCheck = state.snapshot?.canCheckSni == true, viewModel = viewModel)
    }
    state.selfStealForm?.let { form ->
        SelfStealDialog(form, domain = state.snapshot?.domain.orEmpty(), viewModel = viewModel)
    }
    state.sitePageForm?.let { page ->
        SitePageDialog(page, domain = state.sitePageTarget?.domain.orEmpty(), viewModel = viewModel)
    }
    state.confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.inbound_delete_title)) },
            text = { Text(stringResource(R.string.inbound_delete_text, item.remark.ifBlank { "#${item.id}" }, item.clientCount)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (state.offerRemoveSite) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoveSite,
            title = { Text(stringResource(R.string.selfsteal_remove_title)) },
            text = { Text(stringResource(R.string.selfsteal_remove_text)) },
            confirmButton = { TextButton(onClick = viewModel::removeSite) { Text(stringResource(R.string.selfsteal_remove_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissRemoveSite) { Text(stringResource(R.string.selfsteal_keep_site)) } },
        )
    }
}

@Composable
private fun InboundCard(
    item: InboundItem,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onAdvanced: () -> Unit,
    onDelete: () -> Unit,
    /** Self-steal inbounds: change the decoy site's page. */
    onSitePage: (() -> Unit)?,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.remark.ifBlank { "#${item.id}" }, style = MaterialTheme.typography.titleMedium)
                        if (item.selfSteal) {
                            Spacer(Modifier.width(8.dp))
                            Badge(stringResource(R.string.inbound_badge_selfsteal))
                        }
                        item.nodeName?.let { node ->
                            Spacer(Modifier.width(8.dp))
                            Badge(stringResource(R.string.inbound_badge_node, node))
                        }
                    }
                    Text(
                        listOf(item.protocol, item.network, item.security).filter { it.isNotBlank() && it != "none" }.joinToString(" · ") + "  :${item.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                }
                Switch(checked = item.enabled, onCheckedChange = onToggle, enabled = !busy)
            }
            item.reality?.let { reality ->
                Text(
                    if (item.selfSteal) stringResource(R.string.inbound_reality_selfsteal, reality.sni, reality.fingerprint.ifBlank { "—" })
                    else stringResource(R.string.inbound_reality_info, reality.sni.ifBlank { reality.target }, reality.fingerprint.ifBlank { "—" }),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                stringResource(R.string.inbound_usage, item.clientCount, Formatters.bytes(context, item.up + item.down)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (item.quickEditable && !item.selfSteal) {
                    TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.inbound_edit_reality)) }
                }
                if (onSitePage != null) {
                    TextButton(onClick = onSitePage, enabled = !busy) { Text(stringResource(R.string.inbound_edit_site_page)) }
                }
                TextButton(onClick = onAdvanced, enabled = !busy) { Text(stringResource(R.string.inbound_edit_full)) }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddInboundDialog(
    hasDomain: Boolean,
    selfStealRunning: Boolean,
    onSelfSteal: () -> Unit,
    onAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inbound_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AddOption(
                    stringResource(R.string.inbound_option_selfsteal),
                    stringResource(
                        when {
                            !hasDomain -> R.string.error_selfsteal_no_domain
                            selfStealRunning -> R.string.selfsteal_running
                            else -> R.string.inbound_option_selfsteal_text
                        },
                    ),
                    hasDomain && !selfStealRunning,
                    onSelfSteal,
                )
                AddOption(stringResource(R.string.inbound_option_advanced), stringResource(R.string.inbound_option_advanced_text), true, onAdvanced)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddOption(title: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RealityFormDialog(form: RealityForm, canCheck: Boolean, viewModel: InboundsViewModel) {
    AlertDialog(
        onDismissRequest = { if (!form.saving) viewModel.closeRealityForm() },
        title = { Text(stringResource(R.string.inbound_edit_reality_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.inbound_edit_reality_text), style = MaterialTheme.typography.bodySmall)
                SniField(
                    value = form.sni,
                    onChange = viewModel::onSni,
                    error = form.fieldError.takeIf { it == ValidationField.SNI },
                )
                if (canCheck) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = viewModel::checkSni, enabled = !form.checking && !form.saving) {
                            Text(stringResource(R.string.sni_check))
                        }
                        Spacer(Modifier.width(12.dp))
                        when {
                            form.checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            form.check != null -> Text(
                                if (form.check.usable) stringResource(R.string.sni_check_ok, form.check.latencyMs)
                                else stringResource(R.string.sni_check_bad),
                                color = if (form.check.usable) LocalStatusColors.current.success else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                FingerprintField(form.fingerprint, viewModel::onFingerprint)
                form.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = viewModel::saveReality, enabled = !form.saving) {
                if (form.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeRealityForm, enabled = !form.saving) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SelfStealDialog(form: SelfStealForm, domain: String, viewModel: InboundsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeSelfSteal,
        title = { Text(stringResource(R.string.selfsteal_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.selfsteal_text, domain), style = MaterialTheme.typography.bodyMedium)
                if (form.candidates.isNotEmpty()) {
                    Text(stringResource(R.string.selfsteal_where), style = MaterialTheme.typography.titleSmall)
                    form.candidates.forEach { candidate ->
                        ChoiceRow(
                            selected = form.convertId == candidate.id,
                            text = stringResource(R.string.selfsteal_convert, candidate.remark.ifBlank { "#${candidate.id}" }, candidate.clientCount),
                            onClick = { viewModel.onConvert(candidate.id) },
                        )
                    }
                    ChoiceRow(
                        selected = form.convertId == null,
                        text = stringResource(R.string.selfsteal_new_inbound),
                        onClick = { viewModel.onConvert(null) },
                    )
                }
                FingerprintField(form.fingerprint, viewModel::onSelfStealFingerprint)
                SitePageField(form.sitePage, onPick = viewModel::onSelfStealPage, onReset = viewModel::resetSelfStealPage)
            }
        },
        confirmButton = { Button(onClick = viewModel::startSelfSteal) { Text(stringResource(R.string.selfsteal_start)) } },
        dismissButton = { TextButton(onClick = viewModel::closeSelfSteal) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SitePageDialog(page: SitePageChoice, domain: String, viewModel: InboundsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeSitePage,
        title = { Text(stringResource(R.string.site_page_change_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.site_page_change_text, domain), style = MaterialTheme.typography.bodyMedium)
                SitePageField(page, onPick = viewModel::onSitePage, onReset = viewModel::resetSitePage)
            }
        },
        confirmButton = {
            Button(onClick = viewModel::applySitePage, enabled = !page.invalid) { Text(stringResource(R.string.site_page_apply)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closeSitePage) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DecoySiteCard(domain: String, enabled: Boolean, onChangePage: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.site_page_card_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChangePage, enabled = enabled) { Text(stringResource(R.string.site_page_change)) }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SelfStealProgressCard(progress: SelfStealProgress, onDismiss: () -> Unit) {
    val running = progress.state == DeployState.RUNNING
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        when (progress.state) {
                            DeployState.RUNNING -> when (progress.action) {
                                SelfStealAction.INSTALL -> R.string.selfsteal_progress_install
                                SelfStealAction.PAGE -> R.string.selfsteal_progress_page
                                SelfStealAction.REMOVE -> R.string.selfsteal_progress_remove
                            }
                            DeployState.SUCCEEDED -> when (progress.action) {
                                SelfStealAction.INSTALL -> R.string.selfsteal_done_install
                                SelfStealAction.PAGE -> R.string.selfsteal_done_page
                                SelfStealAction.REMOVE -> R.string.selfsteal_done_remove
                            }
                            DeployState.FAILED -> when (progress.action) {
                                SelfStealAction.PAGE -> R.string.selfsteal_failed_page
                                else -> R.string.selfsteal_failed
                            }
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = when (progress.state) {
                        DeployState.SUCCEEDED -> LocalStatusColors.current.success
                        DeployState.FAILED -> MaterialTheme.colorScheme.error
                        DeployState.RUNNING -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.weight(1f),
                )
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            progress.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (progress.state == DeployState.SUCCEEDED && progress.action == SelfStealAction.INSTALL) {
                Text(stringResource(R.string.selfsteal_done_hint), style = MaterialTheme.typography.bodySmall)
            }
            val tail = progress.log.takeLast(LOG_TAIL)
            if (tail.isNotEmpty()) {
                Text(
                    tail.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
            if (!running) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

private const val LOG_TAIL = 12
