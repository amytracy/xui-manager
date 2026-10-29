package dev.autorasqr.xuimanager.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.JobLog
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior

/** Removes the panel and everything else the app installed from the server, after the user types "yes". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallScreen(
    onBack: () -> Unit,
    onDone: (wasNode: Boolean) -> Unit,
    viewModel: UninstallViewModel = hiltViewModel(),
) {
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val deployRunning by viewModel.deployRunning.collectAsStateWithLifecycle()
    var confirmation by rememberSaveable { mutableStateOf("") }
    val job = progress
    val server = targets.lastOrNull()
    val nodes = targets.dropLast(1)

    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(stringResource(R.string.uninstall_title)) },
                navigationIcon = {
                    // The job runs on regardless; only a finished one leaves for a screen that may no longer exist.
                    if (job?.state != DeployState.SUCCEEDED) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (job != null) {
                JobLog(
                    title = stringResource(
                        when (job.state) {
                            DeployState.RUNNING -> R.string.uninstall_running
                            DeployState.SUCCEEDED -> R.string.uninstall_done
                            DeployState.FAILED -> R.string.uninstall_failed
                        },
                    ),
                    state = job.state,
                    log = job.log,
                    error = job.error,
                    onDismiss = { if (job.state == DeployState.SUCCEEDED) onDone(viewModel.wasNode) else viewModel.dismiss() },
                )
                if (job.state == DeployState.SUCCEEDED) {
                    Button(onClick = { onDone(viewModel.wasNode) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.uninstall_finish))
                    }
                }
                if (job.state != DeployState.FAILED) return@Column
            }
            if (server == null) return@Column

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.uninstall_warning, server.name, server.host),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (nodes.isNotEmpty()) {
                        Text(
                            stringResource(R.string.uninstall_nodes, nodes.joinToString { it.name }),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.uninstall_what_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.uninstall_what), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.uninstall_kept),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (deployRunning) {
                Text(stringResource(R.string.uninstall_deploy_running), color = MaterialTheme.colorScheme.error)
            }

            val confirmed = isUninstallConfirmed(confirmation)
            val canStart = confirmed && !deployRunning && job?.state != DeployState.RUNNING
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                label = { Text(stringResource(R.string.uninstall_confirm_label, UNINSTALL_CONFIRM_WORD)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canStart) viewModel.start(confirmation) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.start(confirmation) },
                enabled = canStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (job?.state == DeployState.FAILED) R.string.retry else R.string.uninstall_confirm))
            }
        }
    }
}
