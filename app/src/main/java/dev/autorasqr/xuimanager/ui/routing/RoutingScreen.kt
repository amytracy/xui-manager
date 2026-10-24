package dev.autorasqr.xuimanager.ui.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.ClientRouting
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.RoutingJobProgress
import dev.autorasqr.xuimanager.ui.common.JobLog
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    onBack: () -> Unit,
    viewModel: RoutingViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !ui.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        val routing = ui.state
        if (routing == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                if (ui.error == null) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ui.error!!.message(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.size(12.dp))
                        OutlinedButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!routing.supported) {
                Text(stringResource(R.string.routing_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            ui.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.routing_clients_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.routing_clients_text), style = MaterialTheme.typography.bodySmall)
                    if (routing.clientRouting == ClientRouting.CUSTOM) {
                        Text(
                            stringResource(R.string.routing_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    listOf(
                        Triple(ClientRouting.ROSCOM_DEFAULT, R.string.routing_profile_default, R.string.routing_profile_default_text),
                        Triple(ClientRouting.ROSCOM_WHITELIST, R.string.routing_profile_whitelist, R.string.routing_profile_whitelist_text),
                        Triple(ClientRouting.OFF, R.string.routing_profile_off, R.string.routing_profile_off_text),
                    ).forEach { (profile, title, text) ->
                        ProfileRow(
                            selected = routing.clientRouting == profile,
                            applying = ui.applying == profile,
                            enabled = ui.applying == null,
                            title = stringResource(title),
                            text = stringResource(text),
                            onClick = { viewModel.selectProfile(profile) },
                        )
                    }
                    if (ui.applying != null) {
                        Text(stringResource(R.string.routing_applying), style = MaterialTheme.typography.bodySmall)
                    } else if (ui.applied) {
                        Text(
                            stringResource(R.string.routing_applied),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalStatusColors.current.success,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val running = ui.job?.state == DeployState.RUNNING
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.routing_server_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (running) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        Switch(
                            checked = if (running) ui.job!!.enabling else routing.serverBlocksRu,
                            onCheckedChange = viewModel::toggleServerBlock,
                            enabled = !running,
                        )
                    }
                    Text(stringResource(R.string.routing_server_text), style = MaterialTheme.typography.bodySmall)
                    ui.job?.let { RoutingJobLog(it, onDismiss = viewModel::dismissJob) }
                }
            }

            Text(
                stringResource(R.string.routing_credits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (ui.confirmServerBlock) {
        AlertDialog(
            onDismissRequest = viewModel::dismissServerBlock,
            title = { Text(stringResource(R.string.routing_server_confirm_title)) },
            text = { Text(stringResource(R.string.routing_server_confirm_text)) },
            confirmButton = { TextButton(onClick = viewModel::confirmServerBlock) { Text(stringResource(R.string.routing_server_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissServerBlock) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProfileRow(selected: Boolean, applying: Boolean, enabled: Boolean, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (applying) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoutingJobLog(job: RoutingJobProgress, onDismiss: () -> Unit) {
    JobLog(
        title = stringResource(
            when (job.state) {
                DeployState.RUNNING -> if (job.enabling) R.string.routing_job_enabling else R.string.routing_job_disabling
                DeployState.SUCCEEDED -> if (job.enabling) R.string.routing_job_enabled else R.string.routing_job_disabled
                DeployState.FAILED -> R.string.routing_job_failed
            },
        ),
        state = job.state,
        log = job.log,
        error = job.error,
        onDismiss = onDismiss,
    )
}
