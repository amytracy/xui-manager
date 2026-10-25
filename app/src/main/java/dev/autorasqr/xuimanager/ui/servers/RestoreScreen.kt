package dev.autorasqr.xuimanager.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.ui.common.JobLog

/** Connects the app to the panel already set up on the server (after a phone change) without deploying it again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val deployRunning by viewModel.deployRunning.collectAsStateWithLifecycle()
    val job = progress

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (job != null) {
                JobLog(
                    title = stringResource(
                        when (job.state) {
                            DeployState.RUNNING -> R.string.restore_running
                            DeployState.SUCCEEDED -> R.string.restore_done
                            DeployState.FAILED -> R.string.restore_failed
                        },
                    ),
                    state = job.state,
                    log = job.log,
                    error = job.error,
                    onDismiss = {
                        viewModel.dismiss()
                        if (job.state == DeployState.SUCCEEDED) onDone()
                    },
                )
                if (job.state == DeployState.SUCCEEDED) {
                    Button(
                        onClick = {
                            viewModel.dismiss()
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.restore_finish))
                    }
                }
                if (job.state != DeployState.FAILED) return@Column
            }
            val current = server ?: return@Column

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_intro, current.name, current.host), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.restore_kept_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.restore_kept), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.restore_changed_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(if (current.isNode) R.string.restore_changed_node else R.string.restore_changed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (deployRunning) {
                Text(stringResource(R.string.restore_deploy_running), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = viewModel::start,
                enabled = !deployRunning && job?.state != DeployState.RUNNING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (job?.state == DeployState.FAILED) R.string.retry else R.string.action_restore))
            }
        }
    }
}
