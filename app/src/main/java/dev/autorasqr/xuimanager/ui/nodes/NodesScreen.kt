package dev.autorasqr.xuimanager.ui.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeStatus
import dev.autorasqr.xuimanager.domain.model.PendingNode
import dev.autorasqr.xuimanager.ui.common.Formatters
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    onBack: () -> Unit,
    onAddServer: (masterId: Long) -> Unit,
    onAddPanel: (masterId: Long) -> Unit,
    onEdit: (masterId: Long, nodeId: Int) -> Unit,
    onServer: (serverId: Long) -> Unit,
    onDeploy: (serverId: Long) -> Unit,
    onConnectSsh: (masterId: Long, node: Node) -> Unit,
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.startUpdates() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.stopUpdates() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val probe = message.probe
        val text = when {
            message.error != null -> message.error.message(context)
            probe != null -> if (probe.second.online) {
                resources.getString(R.string.node_probe_ok, probe.first, probe.second.latencyMs)
            } else {
                resources.getString(R.string.node_probe_failed, probe.first, probe.second.error.ifBlank { "?" })
            }
            message.text != null -> resources.getString(message.text)
            else -> null
        }
        viewModel.consumeMessage()
        if (text != null) snackbar.showSnackbar(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nodes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.node_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.snapshot != null,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val snapshot = state.snapshot
            if (snapshot == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val error = state.error
                    if (error == null) {
                        CircularProgressIndicator()
                    } else {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error.message(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                return@PullToRefreshBox
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.error?.let { error ->
                    item { Text(error.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                if (snapshot.nodes.isEmpty() && snapshot.pending.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.nodes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(snapshot.pending, key = { it.key }) { pending ->
                    PendingCard(
                        pending = pending,
                        busy = pending.key in state.busy,
                        onDeploy = { onDeploy(pending.serverId) },
                        onRegister = { viewModel.register(pending) },
                        onServer = { onServer(pending.serverId) },
                        onForget = { viewModel.requestForget(pending) },
                    )
                }
                items(snapshot.nodes, key = { it.key }) { node ->
                    NodeCard(
                        node = node,
                        busy = node.key in state.busy,
                        onEnabled = { viewModel.setEnabled(node, it) },
                        onProbe = { viewModel.probe(node) },
                        onEdit = { onEdit(viewModel.serverId, node.id) },
                        onServer = node.serverId?.let { id -> { onServer(id) } },
                        onConnectSsh = { onConnectSsh(viewModel.serverId, node) },
                        onDelete = { viewModel.requestDelete(node) },
                    )
                }
            }
        }
    }

    if (state.addChooser) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAdd,
            title = { Text(stringResource(R.string.node_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        TextButton(onClick = {
                            viewModel.dismissAdd()
                            onAddServer(viewModel.serverId)
                        }) { Text(stringResource(R.string.node_add_server)) }
                        Text(stringResource(R.string.node_add_server_text), style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        TextButton(onClick = {
                            viewModel.dismissAdd()
                            onAddPanel(viewModel.serverId)
                        }) { Text(stringResource(R.string.node_add_panel)) }
                        Text(stringResource(R.string.node_add_panel_text), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissAdd) { Text(stringResource(R.string.cancel)) } },
        )
    }

    state.confirmDelete?.let { node ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.node_delete_title)) },
            text = { Text(stringResource(R.string.node_delete_text, node.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }

    state.confirmForget?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissForget,
            title = { Text(stringResource(R.string.node_forget_title)) },
            text = { Text(stringResource(R.string.node_forget_text, pending.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::forget) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissForget) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NodeCard(
    node: Node,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onProbe: () -> Unit,
    onEdit: () -> Unit,
    onServer: (() -> Unit)?,
    onConnectSsh: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(node.status)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium)
                    Text(statusText(node), style = MaterialTheme.typography.bodySmall, color = statusColor(node.status))
                }
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                if (!node.transitive) Switch(checked = node.enabled, onCheckedChange = onEnabled, enabled = !busy)
            }
            Text(node.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (node.remark.isNotBlank()) Text(node.remark, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node.transitive) {
                Text(stringResource(R.string.node_transitive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (node.status == NodeStatus.ONLINE) {
                InfoRow(
                    stringResource(R.string.node_load),
                    stringResource(R.string.node_load_value, node.cpuPercent, node.memPercent),
                )
                InfoRow(stringResource(R.string.status_uptime), Formatters.uptime(context, node.uptimeSeconds))
                val running = node.xrayState.equals("running", ignoreCase = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.status_xray), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(if (running) R.string.status_xray_running else R.string.status_xray_stopped, node.xrayVersion.ifBlank { "?" }),
                        color = if (running) LocalStatusColors.current.success else MaterialTheme.colorScheme.error,
                    )
                }
                if (node.xrayError.isNotBlank()) Text(node.xrayError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (node.panelVersion.isNotBlank()) InfoRow(stringResource(R.string.panel_version), node.panelVersion)
            }
            InfoRow(
                stringResource(R.string.node_counts),
                stringResource(R.string.node_counts_value, node.inboundCount, node.clientCount, node.onlineCount),
            )
            if (node.status != NodeStatus.ONLINE && node.lastError.isNotBlank()) {
                Text(node.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!node.transitive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onProbe, enabled = !busy) { Text(stringResource(R.string.node_probe)) }
                    OutlinedButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.edit)) }
                    if (onServer != null) TextButton(onClick = onServer) { Text(stringResource(R.string.node_server)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, enabled = !busy) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
                // A node the app has no SSH access to (added by hand, or deployed from another phone).
                if (onServer == null) {
                    TextButton(onClick = onConnectSsh, enabled = !busy) { Text(stringResource(R.string.node_connect_ssh)) }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(
    pending: PendingNode,
    busy: Boolean,
    onDeploy: () -> Unit,
    onRegister: () -> Unit,
    onServer: () -> Unit,
    onForget: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pending.name, style = MaterialTheme.typography.titleMedium)
                    Text(pending.host, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(
                stringResource(if (pending.deployed) R.string.node_pending_unregistered else R.string.node_pending_not_deployed),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pending.deployed) {
                    OutlinedButton(onClick = onRegister, enabled = !busy) { Text(stringResource(R.string.node_register)) }
                    TextButton(onClick = onServer) { Text(stringResource(R.string.node_server)) }
                } else {
                    OutlinedButton(onClick = onDeploy, enabled = !busy) { Text(stringResource(R.string.action_deploy)) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onForget, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: NodeStatus) {
    Box(Modifier.size(10.dp).background(statusColor(status), CircleShape))
}

@Composable
private fun statusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.ONLINE -> LocalStatusColors.current.success
    NodeStatus.OFFLINE -> MaterialTheme.colorScheme.error
    NodeStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
private fun statusText(node: Node): String = when {
    !node.enabled -> stringResource(R.string.node_status_paused)
    node.status == NodeStatus.ONLINE -> stringResource(R.string.node_status_online, node.latencyMs)
    node.status == NodeStatus.OFFLINE -> stringResource(R.string.node_status_offline)
    else -> stringResource(R.string.node_status_unknown)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
