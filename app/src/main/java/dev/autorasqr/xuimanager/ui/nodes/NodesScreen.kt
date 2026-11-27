package dev.autorasqr.xuimanager.ui.nodes

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeStatus
import dev.autorasqr.xuimanager.domain.model.PendingNode
import dev.autorasqr.xuimanager.domain.model.NodeAction
import dev.autorasqr.xuimanager.domain.model.NodePanelOffer
import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import dev.autorasqr.xuimanager.ui.common.JobLog
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.Formatters
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    /** Null when the screen is a tab of the server screen. */
    onBack: (() -> Unit)?,
    onAddServer: (masterId: Long) -> Unit,
    onAddPanel: (masterId: Long) -> Unit,
    onEdit: (masterId: Long, nodeId: Int) -> Unit,
    onServer: (serverId: Long) -> Unit,
    onDeploy: (serverId: Long) -> Unit,
    onConnectSsh: (masterId: Long, node: Node) -> Unit,
    /** The node's web panel: [nodeId] 0 means [serverId] is the node's own server in the app. */
    onOpenPanel: (serverId: Long, nodeId: Int) -> Unit,
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bulkJob by viewModel.bulkProgress.collectAsStateWithLifecycle()
    LaunchedEffect(bulkJob?.state) {
        val finished = bulkJob?.state
        if (finished == DeployState.SUCCEEDED || finished == DeployState.FAILED) viewModel.onJobFinished()
    }
    val snackbar = remember { SnackbarHostState() }
    // Not tied to the message effect: consuming the message restarts that effect, which would dismiss the snackbar at once.
    val snackbarScope = rememberCoroutineScope()
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
        if (text != null) snackbarScope.launch { snackbar.showSnackbar(text) }
    }

    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(stringResource(R.string.nodes_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    val hasNodes = state.snapshot?.nodes.orEmpty().any { !it.transitive }
                    if (hasNodes) {
                        TextButton(onClick = viewModel::openBulk, enabled = bulkJob?.state != DeployState.RUNNING) {
                            Text(stringResource(R.string.nodes_bulk))
                        }
                    }
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
                bulkJob?.let { job ->
                    item(key = "bulk") {
                        val label = stringResource(actionLabel(job.action))
                        JobLog(
                            title = when (job.state) {
                                DeployState.RUNNING -> stringResource(R.string.nodes_bulk_running, label)
                                DeployState.SUCCEEDED -> stringResource(R.string.nodes_bulk_done, label)
                                DeployState.FAILED -> stringResource(R.string.nodes_bulk_failed, label)
                            },
                            state = job.state, log = job.log, error = job.error,
                            onDismiss = viewModel::dismissBulkJob,
                        )
                    }
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
                        busy = node.key in state.busy || bulkJob?.state == DeployState.RUNNING,
                        panelOffer = state.panelOffers[node.id],
                        xrayOffer = node.serverId?.let { state.xrayOffers[it] },
                        onEnabled = { viewModel.setEnabled(node, it) },
                        onProbe = { viewModel.probe(node) },
                        onEdit = { onEdit(viewModel.serverId, node.id) },
                        onServer = node.serverId?.let { id -> { onServer(id) } },
                        onConnectSsh = { onConnectSsh(viewModel.serverId, node) },
                        onDelete = { viewModel.requestDelete(node) },
                        onAction = { action -> viewModel.requestAction(node, action) },
                        onOpenPanel = {
                            val id = node.serverId
                            if (id != null) onOpenPanel(id, 0) else onOpenPanel(viewModel.serverId, node.id)
                        },
                        viewModel = viewModel,
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

    state.confirmAction?.let { request ->
        val name = request.node.name
        val (title, text, confirm) = when (request.action) {
            NodeAction.RESTART_XRAY -> Triple(
                stringResource(R.string.status_restart_title),
                stringResource(R.string.status_restart_text),
                stringResource(R.string.status_restart_confirm),
            )
            NodeAction.RESTART_PANEL -> Triple(
                stringResource(R.string.panel_restart_title),
                stringResource(R.string.panel_restart_text),
                stringResource(R.string.panel_restart_confirm),
            )
            NodeAction.UPDATE_PANEL -> Triple(
                stringResource(R.string.panel_update_title),
                request.panelOffer?.let { stringResource(R.string.node_panel_update_text, name, it.currentVersion, it.latestVersion) }.orEmpty(),
                stringResource(R.string.panel_update_confirm),
            )
            NodeAction.UPDATE_XRAY -> Triple(
                stringResource(R.string.xray_update_title),
                request.xrayOffer?.let { stringResource(R.string.xray_update_text, it.latestVersion, it.currentVersion.ifBlank { "?" }) }.orEmpty(),
                stringResource(R.string.panel_update_confirm),
            )
            NodeAction.SYSTEM_UPDATE -> Triple(
                stringResource(R.string.sysupdate_confirm_title),
                stringResource(R.string.sysupdate_confirm_text, name),
                stringResource(R.string.sysupdate_confirm),
            )
            NodeAction.REBOOT -> Triple(
                stringResource(R.string.reboot_confirm_title),
                stringResource(R.string.reboot_confirm_text, name),
                stringResource(R.string.reboot_confirm),
            )
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissAction,
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmAction) {
                    Text(confirm, color = if (request.action == NodeAction.REBOOT) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissAction) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (state.bulkChooser) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBulkChooser,
            title = { Text(stringResource(R.string.nodes_bulk_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.nodes_bulk_text), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    NodeAction.entries.forEach { action ->
                        TextButton(onClick = { viewModel.requestBulk(action) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(actionLabel(action)),
                                color = if (action == NodeAction.REBOOT) MaterialTheme.colorScheme.error else Color.Unspecified,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissBulkChooser) { Text(stringResource(R.string.cancel)) } },
        )
    }

    state.confirmBulk?.let { action ->
        val label = stringResource(actionLabel(action))
        AlertDialog(
            onDismissRequest = viewModel::dismissBulk,
            title = { Text(stringResource(R.string.nodes_bulk_confirm_title, label)) },
            text = {
                Text(
                    stringResource(if (action == NodeAction.UPDATE_PANEL) R.string.nodes_bulk_confirm_panel else R.string.nodes_bulk_confirm_text),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmBulk) {
                    Text(
                        stringResource(R.string.nodes_bulk_confirm),
                        color = if (action == NodeAction.REBOOT) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissBulk) { Text(stringResource(R.string.cancel)) } },
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
    panelOffer: NodePanelOffer?,
    xrayOffer: XrayUpdateOffer?,
    onEnabled: (Boolean) -> Unit,
    onProbe: () -> Unit,
    onEdit: () -> Unit,
    onServer: (() -> Unit)?,
    onConnectSsh: () -> Unit,
    onDelete: () -> Unit,
    onAction: (NodeAction) -> Unit,
    onOpenPanel: () -> Unit,
    viewModel: NodesViewModel,
) {
    val context = LocalContext.current
    val nodeServerId = node.serverId
    // Jobs on the node: its own server's (shared with that server's screen) and the 3x-ui update through the master.
    val reboot = nodeServerId?.let { viewModel.rebootProgress(it).collectAsStateWithLifecycle().value }
    val system = nodeServerId?.let { viewModel.systemProgress(it).collectAsStateWithLifecycle().value }
    val xray = nodeServerId?.let { viewModel.xrayProgress(it).collectAsStateWithLifecycle().value }
    val panel by viewModel.panelProgress(node.id).collectAsStateWithLifecycle()
    val jobRunning = listOf(reboot?.state, system?.state, xray?.state, panel?.state).any { it == DeployState.RUNNING }
    val jobStates = listOf(reboot?.state, system?.state, xray?.state, panel?.state)
    LaunchedEffect(jobStates) {
        if (jobStates.any { it == DeployState.SUCCEEDED || it == DeployState.FAILED }) viewModel.onJobFinished()
    }

    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(node.status)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium)
                    Text(statusText(node), style = MaterialTheme.typography.bodySmall, color = statusColor(node.status))
                }
                if (busy || jobRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
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
            panelOffer?.let {
                Text(
                    stringResource(R.string.node_update_line, "3x-ui", it.currentVersion, it.latestVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            xrayOffer?.let {
                Text(
                    stringResource(R.string.node_update_line, "Xray", it.currentVersion.ifBlank { "?" }, it.latestVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    ManageMenu(
                        hasSsh = nodeServerId != null,
                        enabled = !busy && !jobRunning,
                        panelOffer = panelOffer,
                        xrayOffer = xrayOffer,
                        onAction = { action -> if (action.needsSsh && nodeServerId == null) onConnectSsh() else onAction(action) },
                        onOpenPanel = onOpenPanel,
                        onServer = onServer,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, enabled = !busy) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
                // A node the app has no SSH access to (added by hand, or deployed from another phone).
                if (nodeServerId == null) {
                    TextButton(onClick = onConnectSsh, enabled = !busy) { Text(stringResource(R.string.node_connect_ssh)) }
                }
            }
            panel?.let { job ->
                JobLog(
                    title = when (job.state) {
                        DeployState.RUNNING -> stringResource(R.string.panel_update_running, job.targetVersion)
                        DeployState.SUCCEEDED -> stringResource(R.string.panel_update_done, job.targetVersion)
                        DeployState.FAILED -> stringResource(R.string.panel_update_failed)
                    },
                    state = job.state, log = job.log, error = job.error,
                    onDismiss = { viewModel.dismissPanel(node.id) },
                )
            }
            if (nodeServerId != null) {
                xray?.let { job ->
                    JobLog(
                        title = when (job.state) {
                            DeployState.RUNNING -> stringResource(R.string.xray_update_running, job.targetVersion)
                            DeployState.SUCCEEDED -> stringResource(R.string.xray_update_done, job.targetVersion)
                            DeployState.FAILED -> stringResource(R.string.xray_update_failed)
                        },
                        state = job.state, log = job.log, error = job.error,
                        onDismiss = { viewModel.dismissXray(nodeServerId) },
                    )
                }
                system?.let { job ->
                    JobLog(
                        title = stringResource(
                            when (job.state) {
                                DeployState.RUNNING -> R.string.sysupdate_running
                                DeployState.SUCCEEDED -> if (job.rebooting) R.string.sysupdate_rebooting else R.string.sysupdate_done
                                DeployState.FAILED -> R.string.sysupdate_failed
                            },
                        ),
                        state = job.state, log = job.log, error = job.error,
                        onDismiss = { viewModel.dismissSystem(nodeServerId) },
                    )
                }
                reboot?.let { job ->
                    JobLog(
                        title = stringResource(
                            when (job.state) {
                                DeployState.RUNNING -> R.string.reboot_running
                                DeployState.SUCCEEDED -> R.string.reboot_done
                                DeployState.FAILED -> R.string.reboot_failed
                            },
                        ),
                        state = job.state, log = job.log, error = job.error,
                        onDismiss = { viewModel.dismissReboot(nodeServerId) },
                    )
                }
            }
        }
    }
    // A node with its own server in the app opens that server's screen on tap.
    if (onServer != null) Card(onClick = onServer, modifier = Modifier.fillMaxWidth()) { content() } else Card(Modifier.fillMaxWidth()) { content() }
}

/** Everything that can be done to a node; the SSH-only actions of a node without SSH lead to "Connect over SSH". */
@Composable
private fun ManageMenu(
    hasSsh: Boolean,
    enabled: Boolean,
    panelOffer: NodePanelOffer?,
    xrayOffer: XrayUpdateOffer?,
    onAction: (NodeAction) -> Unit,
    onOpenPanel: () -> Unit,
    onServer: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(stringResource(R.string.node_manage))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val sshHint: (@Composable () -> Unit)? = if (hasSsh) null else ({
                Text(stringResource(R.string.node_needs_ssh), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
            fun item(action: NodeAction): () -> Unit = { open = false; onAction(action) }
            DropdownMenuItem(text = { Text(stringResource(R.string.status_restart_xray)) }, onClick = item(NodeAction.RESTART_XRAY), trailingIcon = sshHint)
            DropdownMenuItem(text = { Text(stringResource(R.string.action_restart_panel)) }, onClick = item(NodeAction.RESTART_PANEL), trailingIcon = sshHint)
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(panelOffer?.let { stringResource(R.string.action_update_panel, it.latestVersion) } ?: stringResource(R.string.node_menu_update_panel))
                },
                onClick = item(NodeAction.UPDATE_PANEL),
            )
            DropdownMenuItem(
                text = {
                    Text(xrayOffer?.let { stringResource(R.string.action_update_xray, it.latestVersion) } ?: stringResource(R.string.node_menu_update_xray))
                },
                onClick = item(NodeAction.UPDATE_XRAY),
                trailingIcon = sshHint,
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.action_system_update)) }, onClick = item(NodeAction.SYSTEM_UPDATE), trailingIcon = sshHint)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_reboot_server), color = MaterialTheme.colorScheme.error) },
                onClick = item(NodeAction.REBOOT),
                trailingIcon = sshHint,
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.node_menu_web_panel)) }, onClick = { open = false; onOpenPanel() })
            if (onServer != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.node_menu_server)) }, onClick = { open = false; onServer() })
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

@StringRes
private fun actionLabel(action: NodeAction): Int = when (action) {
    NodeAction.RESTART_XRAY -> R.string.status_restart_xray
    NodeAction.RESTART_PANEL -> R.string.action_restart_panel
    NodeAction.UPDATE_PANEL -> R.string.node_menu_update_panel
    NodeAction.UPDATE_XRAY -> R.string.node_menu_update_xray
    NodeAction.SYSTEM_UPDATE -> R.string.action_system_update
    NodeAction.REBOOT -> R.string.action_reboot_server
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
