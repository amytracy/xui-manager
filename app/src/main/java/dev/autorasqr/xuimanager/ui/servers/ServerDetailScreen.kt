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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.domain.model.PanelLogin
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.ui.common.Clipboard
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
    onBack: (() -> Unit)? = null,
    viewModel: ServerDetailViewModel = hiltViewModel(),
) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    val deployRunning by viewModel.deployRunning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val panelLogin by viewModel.panelLogin.collectAsStateWithLifecycle()
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
            PanelCard(
                server = current,
                deployRunning = deployRunning,
                onDeploy = { onDeploy(current.id) },
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
            SshCard(current)
        }
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
private fun SshCard(server: Server) {
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
        }
    }
}

@Composable
private fun PanelCard(
    server: Server,
    deployRunning: Boolean,
    onDeploy: () -> Unit,
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
            if (panel == null) {
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
