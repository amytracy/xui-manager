package dev.autorasqr.xuimanager.ui.deploy

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.Fingerprints
import dev.autorasqr.xuimanager.data.deploy.SniPresets
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployProgress
import dev.autorasqr.xuimanager.domain.model.DeployStage
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.DeployWarning
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundSecurity
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.ui.common.FingerprintField
import dev.autorasqr.xuimanager.ui.common.HostKeyDialog
import dev.autorasqr.xuimanager.ui.common.SelectField
import dev.autorasqr.xuimanager.ui.common.SniField
import dev.autorasqr.xuimanager.ui.common.label
import dev.autorasqr.xuimanager.ui.common.labelRes
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.common.messageRes
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployScreen(
    onBack: () -> Unit,
    onClients: (Long) -> Unit,
    onNodes: (masterId: Long) -> Unit,
    viewModel: DeployViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.start()
    }
    val startWithPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.server?.isNode == true) R.string.deploy_node_title else R.string.deploy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val progress = state.progress
        if (state.jobId == null) {
            SetupContent(
                state = state,
                viewModel = viewModel,
                onStart = startWithPermission,
                modifier = Modifier.padding(padding),
            )
        } else {
            ProgressContent(
                progress = progress,
                onClients = { onClients(viewModel.serverId) },
                masterId = state.server?.masterServerId,
                onNodes = onNodes,
                onRetry = viewModel::reset,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (state.askExistingAction) {
        ExistingInstallDialog(
            version = state.precheck?.existingVersion,
            onChoose = viewModel::chooseExistingAction,
        )
    }
    state.hostKeyPrompt?.let { prompt ->
        HostKeyDialog(
            host = prompt.host,
            presented = prompt.presented,
            previous = prompt.previous,
            onTrust = viewModel::trustHostKey,
            onDismiss = viewModel::dismissHostKey,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupContent(
    state: DeployUiState,
    viewModel: DeployViewModel,
    onStart: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.detachedJobId != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.deploy_in_progress), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.deploy_detached_text))
                    Button(onClick = viewModel::resume) { Text(stringResource(R.string.deploy_resume)) }
                }
            }
            return@Column
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DeployMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { viewModel.onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, DeployMode.entries.size),
                ) {
                    Text(stringResource(if (mode == DeployMode.ONE_CLICK) R.string.deploy_mode_one_click else R.string.deploy_mode_manual))
                }
            }
        }

        when (state.mode) {
            DeployMode.ONE_CLICK -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.deploy_one_click_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.deploy_one_click_text, DeployConfig.XUI_VERSION), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val domain = state.server?.domain
                if (domain != null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(true, false).forEachIndexed { index, selfSteal ->
                            SegmentedButton(
                                selected = state.selfSteal == selfSteal,
                                onClick = { viewModel.onSelfSteal(selfSteal) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) {
                                Text(stringResource(if (selfSteal) R.string.deploy_disguise_own else R.string.deploy_disguise_other))
                            }
                        }
                    }
                    Text(
                        if (state.selfSteal) stringResource(R.string.deploy_disguise_own_text, domain)
                        else stringResource(R.string.deploy_disguise_other_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (domain == null || !state.selfSteal) {
                    val autoLabel = stringResource(R.string.deploy_sni_auto)
                    SelectField(
                        label = stringResource(R.string.deploy_sni_label),
                        selected = state.sni,
                        options = listOf<String?>(null) + SniPresets.targets,
                        text = { it ?: autoLabel },
                        onSelect = viewModel::onSni,
                    )
                }
                FingerprintField(state.fingerprint, viewModel::onFingerprint)
            }
            DeployMode.MANUAL -> ManualForm(state.manual, state.fingerprint, viewModel)
        }

        // Subscriptions of a node's inbounds are served by the main panel, so its routing settings apply there.
        if (state.server?.isNode != true) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.deploy_routing_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.deploy_routing_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.clientRouting, onCheckedChange = viewModel::onClientRouting)
        }

        state.precheck?.let { precheck ->
            Text(
                stringResource(
                    R.string.deploy_server_info,
                    listOf(precheck.os, precheck.osVersion).filter { it.isNotBlank() }.joinToString(" "),
                    precheck.arch,
                    state.server?.sshUser.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }

        Button(onClick = onStart, enabled = !state.prechecking && state.server != null, modifier = Modifier.fillMaxWidth()) {
            if (state.prechecking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.deploy_prechecking))
            } else {
                Text(stringResource(R.string.deploy_start))
            }
        }
    }
}

@Composable
private fun ManualForm(form: ManualForm, fingerprint: String, viewModel: DeployViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.deploy_manual_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.deploy_manual_text, DeployConfig.XUI_VERSION), style = MaterialTheme.typography.bodyMedium)
        }
    }
    SelectField(
        label = stringResource(R.string.field_protocol),
        selected = form.protocol,
        options = InboundProtocol.entries,
        text = { it.label() },
        onSelect = viewModel::onProtocol,
    )
    SelectField(
        label = stringResource(R.string.field_transport),
        selected = form.transport,
        options = form.transports,
        text = { it.label() },
        onSelect = viewModel::onTransport,
        error = form.fieldError.takeIf { it == ValidationField.TRANSPORT },
    )
    Text(
        stringResource(R.string.field_security, stringResource(form.security.labelRes())),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = form.port,
        onValueChange = viewModel::onPort,
        label = { Text(stringResource(R.string.field_inbound_port)) },
        isError = form.fieldError == ValidationField.INBOUND_PORT,
        supportingText = {
            Text(
                stringResource(
                    if (form.fieldError == ValidationField.INBOUND_PORT) ValidationField.INBOUND_PORT.messageRes()
                    else R.string.field_inbound_port_hint,
                ),
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.security == InboundSecurity.REALITY) {
        SniField(
            value = form.sni,
            onChange = viewModel::onManualSni,
            error = form.fieldError.takeIf { it == ValidationField.SNI },
        )
        FingerprintField(fingerprint, viewModel::onFingerprint)
    }
}


@Composable
private fun ProgressContent(
    progress: DeployProgress?,
    onClients: () -> Unit,
    masterId: Long?,
    onNodes: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    if (progress == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val logState = rememberLazyListState()
    LaunchedEffect(progress.log.size) {
        if (progress.log.isNotEmpty()) logState.scrollToItem(progress.log.lastIndex)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StageList(progress)
        when (progress.state) {
            DeployState.RUNNING -> Text(
                stringResource(R.string.deploy_running_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeployState.SUCCEEDED -> {
                Text(stringResource(R.string.deploy_success_title), style = MaterialTheme.typography.titleLarge, color = LocalStatusColors.current.success)
                progress.warnings.forEach { WarningCard(it) }
                if (masterId != null) {
                    Button(onClick = { onNodes(masterId) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.deploy_go_nodes)) }
                } else {
                    Button(onClick = onClients, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.deploy_go_clients)) }
                }
            }
            DeployState.FAILED -> {
                Text(stringResource(R.string.deploy_failed_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                progress.error?.let { Text(it.message()) }
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.deploy_try_again)) }
            }
        }
        Text(stringResource(R.string.deploy_log), style = MaterialTheme.typography.labelLarge)
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = logState,
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 120.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(progress.log) { _, line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun StageList(progress: DeployProgress) {
    val stages = DeployStage.entries
    val currentIndex = stages.indexOf(progress.stage)
    val visible = stages.filter { it != DeployStage.DONE && it !in progress.skippedStages }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEach { stage ->
            val index = stages.indexOf(stage)
            val status = when {
                progress.state == DeployState.SUCCEEDED || index < currentIndex -> StageStatus.DONE
                index == currentIndex && progress.state == DeployState.FAILED -> StageStatus.FAILED
                index == currentIndex -> StageStatus.ACTIVE
                else -> StageStatus.PENDING
            }
            if (status != StageStatus.PENDING || index <= currentIndex + 2) StageRow(stage, status)
        }
    }
}

private enum class StageStatus { DONE, ACTIVE, FAILED, PENDING }

@Composable
private fun StageRow(stage: DeployStage, status: StageStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StageStatus.DONE -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = LocalStatusColors.current.success)
                StageStatus.ACTIVE -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                StageStatus.FAILED -> Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                StageStatus.PENDING -> Box(
                    Modifier
                        .size(12.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .background(Color.Transparent, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(stage.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == StageStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WarningCard(warning: DeployWarning) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = LocalStatusColors.current.warning)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when (warning) {
                        DeployWarning.SUBSCRIPTION_PLAIN_HTTP -> R.string.warning_sub_http
                        DeployWarning.SNI_UNVERIFIED -> R.string.warning_sni
                        DeployWarning.NODE_NOT_REGISTERED -> R.string.warning_node_not_registered
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ExistingInstallDialog(version: String?, onChoose: (ExistingInstallAction?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(stringResource(R.string.existing_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.existing_text, version ?: stringResource(R.string.unknown_version)))
                Column {
                    TextButton(onClick = { onChoose(ExistingInstallAction.UPDATE) }) { Text(stringResource(R.string.existing_update)) }
                    Text(stringResource(R.string.existing_update_text), style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    TextButton(onClick = { onChoose(ExistingInstallAction.ADOPT) }) { Text(stringResource(R.string.existing_adopt)) }
                    Text(stringResource(R.string.existing_adopt_text), style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    TextButton(onClick = { onChoose(ExistingInstallAction.CLEAN) }) {
                        Text(stringResource(R.string.existing_clean), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.existing_clean_text), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}
