package dev.autorasqr.xuimanager.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.BuildConfig
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.PanelLogin
import dev.autorasqr.xuimanager.domain.model.PanelUpdateOffer
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.ui.common.Clipboard
import dev.autorasqr.xuimanager.ui.common.JobLog
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.Formatters
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    onEdit: (Long) -> Unit,
    onDeploy: (Long) -> Unit,
    onClients: (Long) -> Unit,
    onInbounds: (Long) -> Unit,
    onRouting: (Long) -> Unit,
    onNodes: (Long) -> Unit,
    onOpenPanel: (Long) -> Unit,
    onRestore: (Long) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    val deployRunning by viewModel.deployRunning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val panelLogin by viewModel.panelLogin.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val updateDialog by viewModel.updateDialog.collectAsStateWithLifecycle()
    val panelUpdate by viewModel.panelUpdate.collectAsStateWithLifecycle()
    val xrayUpdate by viewModel.xrayUpdate.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val systemUpdate by viewModel.systemUpdate.collectAsStateWithLifecycle()
    val confirmSystemUpdate by viewModel.confirmSystemUpdate.collectAsStateWithLifecycle()
    val installedPanel by viewModel.installedPanel.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshDeployState()
        viewModel.startStatusUpdates()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.stopStatusUpdates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server?.name.orEmpty()) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(viewModel.serverId) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.server_settings))
                    }
                },
            )
        },
    ) { padding ->
        val current = server
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (deployRunning) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.deploy_in_progress), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { onDeploy(current.id) }) { Text(stringResource(R.string.action_open_progress)) }
                    }
                }
            }
            panelUpdate?.let { job ->
                JobLog(
                    title = when (job.state) {
                        DeployState.RUNNING -> stringResource(R.string.panel_update_running, job.targetVersion)
                        DeployState.SUCCEEDED -> stringResource(R.string.panel_update_done, job.targetVersion)
                        DeployState.FAILED -> stringResource(R.string.panel_update_failed)
                    },
                    state = job.state,
                    log = job.log,
                    error = job.error,
                    onDismiss = viewModel::dismissPanelUpdate,
                )
            }
            xrayUpdate?.let { job ->
                JobLog(
                    title = when (job.state) {
                        DeployState.RUNNING -> stringResource(R.string.xray_update_running, job.targetVersion)
                        DeployState.SUCCEEDED -> stringResource(R.string.xray_update_done, job.targetVersion)
                        DeployState.FAILED -> stringResource(R.string.xray_update_failed)
                    },
                    state = job.state,
                    log = job.log,
                    error = job.error,
                    onDismiss = viewModel::dismissXrayUpdate,
                )
            }
            systemUpdate?.let { job ->
                JobLog(
                    title = stringResource(
                        when (job.state) {
                            DeployState.RUNNING -> R.string.sysupdate_running
                            DeployState.SUCCEEDED -> if (job.rebooting) R.string.sysupdate_rebooting else R.string.sysupdate_done
                            DeployState.FAILED -> R.string.sysupdate_failed
                        },
                    ),
                    state = job.state,
                    log = job.log,
                    error = job.error,
                    onDismiss = viewModel::dismissSystemUpdate,
                )
            }
            PanelCard(
                server = current,
                deployRunning = deployRunning,
                installedPanel = installedPanel,
                onDeploy = { onDeploy(current.id) },
                onRestore = { onRestore(current.id) },
                onClients = { onClients(current.id) },
                onInbounds = { onInbounds(current.id) },
                onRouting = { onRouting(current.id) },
                onNodes = { onNodes(current.id) },
                onOpenPanel = { onOpenPanel(current.id) },
            )
            panelLogin?.let { PanelLoginCard(it) }
            if (current.panel != null) {
                StatusCard(status, onRestart = viewModel::requestRestart)
            }
            val busy = deployRunning || panelUpdate?.state == DeployState.RUNNING ||
                xrayUpdate?.state == DeployState.RUNNING || systemUpdate?.state == DeployState.RUNNING
            UpdatesCard(
                server = current,
                xrayVersion = status.status?.xrayVersion,
                updates = updates,
                busy = busy,
                onCheck = viewModel::checkUpdates,
                onOpenRelease = { url -> uriHandler.openUri(url) },
                onUpdatePanel = viewModel::startPanelUpdate,
                onUpdateXray = viewModel::startXrayUpdate,
            )
            SshCard(
                server = current,
                updateEnabled = !deployRunning && systemUpdate?.state != DeployState.RUNNING,
                onSystemUpdate = viewModel::requestSystemUpdate,
            )
        }
    }

    val appOffer = updates.appOffer
    val panelOffer = updates.panelOffer
    val xrayOffer = updates.xrayOffer
    when (updateDialog) {
        UpdateDialog.APP -> if (appOffer != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text(stringResource(R.string.app_update_title)) },
                text = {
                    val notes = appOffer.notes.lines().take(APP_NOTES_LINES).joinToString("\n").trim()
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.app_update_text, appOffer.latestVersion, appOffer.currentVersion))
                        if (notes.isNotEmpty()) Text(notes, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        uriHandler.openUri(appOffer.releaseUrl)
                        viewModel.dismissUpdateDialog()
                    }) { Text(stringResource(R.string.app_update_open)) }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissUpdateDialog) { Text(stringResource(R.string.panel_update_later)) } },
            )
        }
        UpdateDialog.PANEL -> if (panelOffer != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text(stringResource(R.string.panel_update_title)) },
                text = { Text(updateOfferText(panelOffer)) },
                confirmButton = { TextButton(onClick = viewModel::startPanelUpdate) { Text(stringResource(R.string.panel_update_confirm)) } },
                dismissButton = { TextButton(onClick = viewModel::dismissUpdateDialog) { Text(stringResource(R.string.panel_update_later)) } },
            )
        }
        UpdateDialog.XRAY -> if (xrayOffer != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdateDialog,
                title = { Text(stringResource(R.string.xray_update_title)) },
                text = { Text(stringResource(R.string.xray_update_text, xrayOffer.latestVersion, xrayOffer.currentVersion.ifBlank { "?" })) },
                confirmButton = { TextButton(onClick = viewModel::startXrayUpdate) { Text(stringResource(R.string.panel_update_confirm)) } },
                dismissButton = { TextButton(onClick = viewModel::dismissUpdateDialog) { Text(stringResource(R.string.panel_update_later)) } },
            )
        }
        null -> Unit
    }

    if (confirmSystemUpdate) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSystemUpdateConfirm,
            title = { Text(stringResource(R.string.sysupdate_confirm_title)) },
            text = { Text(stringResource(R.string.sysupdate_confirm_text, server?.name.orEmpty())) },
            confirmButton = { TextButton(onClick = viewModel::startSystemUpdate) { Text(stringResource(R.string.sysupdate_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissSystemUpdateConfirm) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (status.confirmRestart) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestart,
            title = { Text(stringResource(R.string.status_restart_title)) },
            text = { Text(stringResource(R.string.status_restart_text)) },
            confirmButton = { TextButton(onClick = viewModel::restartXray) { Text(stringResource(R.string.status_restart_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissRestart) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StatusCard(state: StatusUiState, onRestart: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.loading || state.restarting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val status = state.status
            if (status == null) {
                if (state.error == null) Text(stringResource(R.string.status_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InfoRow(stringResource(R.string.status_cpu), stringResource(R.string.percent_value, status.cpuPercent))
                InfoRow(
                    stringResource(R.string.status_memory),
                    stringResource(R.string.traffic_of, Formatters.bytes(context, status.memUsed), Formatters.bytes(context, status.memTotal)),
                )
                if (status.memTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (status.memUsed.toFloat() / status.memTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (status.loads.isNotEmpty()) {
                    val loads = status.loads.take(3).map { stringResource(R.string.decimal_value, it) }
                    InfoRow(stringResource(R.string.status_load), loads.joinToString(" · "))
                }
                InfoRow(stringResource(R.string.status_uptime), Formatters.uptime(context, status.uptimeSeconds))
                val running = status.xrayState.equals("running", ignoreCase = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.status_xray), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(if (running) R.string.status_xray_running else R.string.status_xray_stopped, status.xrayVersion.ifBlank { "?" }),
                        color = if (running) LocalStatusColors.current.success else MaterialTheme.colorScheme.error,
                    )
                }
                status.xrayError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                InfoRow(stringResource(R.string.panel_version), status.panelVersion)
            }
            if (state.restarted) {
                Text(stringResource(R.string.status_xray_restarted), color = LocalStatusColors.current.success, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onRestart, enabled = !state.restarting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.status_restart_xray))
            }
        }
    }
}

@Composable
private fun updateOfferText(offer: PanelUpdateOffer): String {
    val lines = buildList {
        add(stringResource(R.string.panel_update_text, offer.latestVersion))
        offer.master?.let { add(stringResource(R.string.panel_update_master, it.name, it.currentVersion)) }
        offer.nodes.forEach { add(stringResource(R.string.panel_update_node, it.name, it.currentVersion)) }
    }
    return lines.joinToString("\n") + "\n\n" + stringResource(R.string.panel_update_note)
}

private const val APP_NOTES_LINES = 12

/** The app, 3x-ui and Xray: what runs now, what is newer, and the buttons to get it. */
@Composable
private fun UpdatesCard(
    server: Server,
    xrayVersion: String?,
    updates: UpdatesUiState,
    busy: Boolean,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onUpdatePanel: () -> Unit,
    onUpdateXray: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (updates.checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            UpdateRow(stringResource(R.string.updates_app), BuildConfig.VERSION_NAME, updates.app) { offer ->
                stringResource(R.string.updates_available, offer.latestVersion)
            }
            updates.appOffer?.let { offer ->
                OutlinedButton(onClick = { onOpenRelease(offer.releaseUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_update_open))
                }
            }

            val panel = server.panel
            if (panel != null) {
                if (!server.isNode) {
                    UpdateRow(stringResource(R.string.updates_panel), panel.version, updates.panel) { offer ->
                        if (offer.master != null) {
                            stringResource(R.string.updates_available, offer.latestVersion)
                        } else {
                            stringResource(R.string.panel_update_available_nodes, offer.nodes.joinToString { it.name })
                        }
                    }
                    updates.panelOffer?.let { offer ->
                        if (offer.master != null && offer.nodes.isNotEmpty()) {
                            Text(
                                stringResource(R.string.panel_update_available_nodes, offer.nodes.joinToString { it.name }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Button(onClick = onUpdatePanel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_update_panel, offer.latestVersion))
                        }
                    }
                }
                UpdateRow(stringResource(R.string.updates_xray), xrayVersion?.ifBlank { null } ?: "?", updates.xray) { offer ->
                    stringResource(R.string.updates_available, offer.latestVersion)
                }
                updates.xrayOffer?.let { offer ->
                    Button(onClick = onUpdateXray, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_update_xray, offer.latestVersion))
                    }
                }
            }

            OutlinedButton(onClick = onCheck, enabled = !updates.checking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.updates_check))
            }
        }
    }
}

@Composable
private fun <T> UpdateRow(label: String, current: String, check: UpdateCheck<T>?, available: @Composable (T) -> String) {
    Column(Modifier.fillMaxWidth()) {
        InfoRow(label, current)
        val (text, color) = when (check) {
            null -> stringResource(R.string.updates_checking) to MaterialTheme.colorScheme.onSurfaceVariant
            UpdateCheck.UpToDate -> stringResource(R.string.updates_up_to_date) to LocalStatusColors.current.success
            is UpdateCheck.Available -> available(check.offer) to MaterialTheme.colorScheme.primary
            is UpdateCheck.Unavailable -> {
                val error = check.error
                if (error == null) {
                    stringResource(R.string.updates_not_supported) to MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    stringResource(R.string.updates_failed, error.message()) to MaterialTheme.colorScheme.error
                }
            }
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun SshCard(server: Server, updateEnabled: Boolean, onSystemUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.server_ssh), style = MaterialTheme.typography.titleMedium)
            Text("${server.sshUser}@${server.host}:${server.sshPort}")
            server.domain?.let { InfoRow(stringResource(R.string.server_domain), it) }
            Text(stringResource(R.string.server_host_key), style = MaterialTheme.typography.labelMedium)
            val key = server.hostKey
            if (key == null) {
                Text(stringResource(R.string.host_key_not_pinned), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SelectionContainer {
                    Text(
                        "${key.algorithm}\n${key.fingerprint}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(onClick = onSystemUpdate, enabled = updateEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_system_update))
            }
        }
    }
}

@Composable
private fun PanelCard(
    server: Server,
    deployRunning: Boolean,
    installedPanel: InstalledPanel?,
    onDeploy: () -> Unit,
    onRestore: () -> Unit,
    onClients: () -> Unit,
    onInbounds: () -> Unit,
    onRouting: () -> Unit,
    onNodes: () -> Unit,
    onOpenPanel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.panel_section), style = MaterialTheme.typography.titleMedium)
            val panel = server.panel
            if (panel == null && installedPanel != null) {
                Text(stringResource(R.string.panel_found_text, installedPanel.version.ifEmpty { "3x-ui" }))
                Button(onClick = onRestore, enabled = !deployRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_restore))
                }
                OutlinedButton(onClick = onDeploy, enabled = !deployRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_redeploy))
                }
            } else if (panel == null) {
                Text(stringResource(R.string.panel_not_deployed_text, DeployConfig.XUI_VERSION))
                Button(onClick = onDeploy, enabled = !deployRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_deploy))
                }
            } else {
                InfoRow(stringResource(R.string.panel_version), panel.version)
                InfoRow(stringResource(R.string.panel_port), panel.port.toString())
                InfoRow(stringResource(R.string.panel_subscription), "${panel.subScheme.uppercase()} :${panel.subPort}")
                InfoRow(stringResource(R.string.panel_deployed_at), Formatters.dateTime(panel.deployedAt))
                if (server.isNode) {
                    // Clients, inbounds and subscriptions of a node live on the main panel.
                    Text(
                        stringResource(if (server.nodeId != null) R.string.node_server_managed else R.string.node_server_unregistered),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = onClients, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_clients)) }
                    Button(onClick = onInbounds, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_inbounds)) }
                    Button(onClick = onRouting, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_routing)) }
                    if ((PanelVersion.major(panel.version) ?: 0) >= 3) {
                        Button(onClick = onNodes, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_nodes)) }
                    }
                }
                OutlinedButton(onClick = onOpenPanel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_panel)) }
                OutlinedButton(onClick = onDeploy, enabled = !deployRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_redeploy))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/** Address, login and password for signing in to the panel in any browser. */
@Composable
private fun PanelLoginCard(login: PanelLogin) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    // No screenshots or recents thumbnail while the password is on screen.
    if (revealed) SecureScreen()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.panel_login_title), style = MaterialTheme.typography.titleMedium)
            CopyRow(stringResource(R.string.panel_address), login.url, login.url)
            CopyRow(stringResource(R.string.panel_username), login.username, login.username)
            val password = login.password
            if (password == null) {
                Text(stringResource(R.string.panel_password_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CopyRow(
                    label = stringResource(R.string.panel_password),
                    shown = if (revealed) password else "••••••••••••",
                    copyValue = password,
                    sensitive = true,
                    extra = {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(stringResource(if (revealed) R.string.hide else R.string.show))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CopyRow(
    label: String,
    shown: String,
    copyValue: String,
    sensitive: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
        extra()
        TextButton(onClick = { Clipboard.copy(context, label, copyValue, sensitive) }) {
            Text(stringResource(R.string.copy))
        }
    }
}
