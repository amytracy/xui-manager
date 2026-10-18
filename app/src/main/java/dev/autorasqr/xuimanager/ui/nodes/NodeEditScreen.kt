package dev.autorasqr.xuimanager.ui.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.SelectField
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.common.messageRes
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: NodeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    // The API token is a full-access credential of the node's panel.
    SecureScreen()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.node_edit_title_new else R.string.node_edit_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isNew) {
                Text(stringResource(R.string.node_edit_intro), style = MaterialTheme.typography.bodyMedium)
            }
            TextInput(state.name, viewModel::onName, stringResource(R.string.field_name), error = state.fieldError.takeIf { it == ValidationField.NAME })
            TextInput(state.remark, viewModel::onRemark, stringResource(R.string.node_field_remark))
            SelectField(
                label = stringResource(R.string.node_field_scheme),
                selected = state.scheme,
                options = listOf("https", "http"),
                text = { it },
                onSelect = viewModel::onScheme,
            )
            TextInput(
                state.address, viewModel::onAddress, stringResource(R.string.node_field_address),
                error = state.fieldError.takeIf { it == ValidationField.HOST },
                hint = stringResource(R.string.node_field_address_hint),
                keyboard = KeyboardType.Uri,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    TextInput(
                        state.port, viewModel::onPort, stringResource(R.string.node_field_port),
                        error = state.fieldError.takeIf { it == ValidationField.PORT }, keyboard = KeyboardType.Number,
                    )
                }
                Box(Modifier.weight(2f)) {
                    TextInput(state.basePath, viewModel::onBasePath, stringResource(R.string.node_field_base_path), keyboard = KeyboardType.Uri, monospace = true)
                }
            }
            TokenInput(
                value = state.apiToken,
                onChange = viewModel::onApiToken,
                hint = stringResource(
                    when {
                        state.tlsVerifyMode == "mtls" -> R.string.node_field_token_mtls
                        state.hasStoredToken -> R.string.node_field_token_keep
                        else -> R.string.node_field_token_hint
                    },
                ),
            )
            SwitchRow(stringResource(R.string.node_field_enabled), null, state.enabled, viewModel::onEnabled)
            SwitchRow(
                stringResource(R.string.node_field_private),
                stringResource(R.string.node_field_private_hint),
                state.allowPrivateAddress,
                viewModel::onAllowPrivate,
            )

            Text(stringResource(R.string.node_section_tls), style = MaterialTheme.typography.titleSmall)
            if (state.scheme == "http") {
                Text(stringResource(R.string.node_tls_http), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                SelectField(
                    label = stringResource(R.string.node_field_tls),
                    selected = state.tlsVerifyMode,
                    options = listOf("verify", "pin", "skip", "mtls"),
                    text = { stringResource(tlsLabel(it)) },
                    onSelect = viewModel::onTlsMode,
                )
                when (state.tlsVerifyMode) {
                    "skip" -> Text(stringResource(R.string.node_tls_skip_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    "mtls" -> Text(stringResource(R.string.node_tls_mtls_hint), style = MaterialTheme.typography.bodySmall)
                    "pin" -> {
                        TextInput(
                            state.pinnedCertSha256, viewModel::onPin, stringResource(R.string.node_field_pin),
                            hint = stringResource(R.string.node_field_pin_hint), monospace = true,
                        )
                        OutlinedButton(onClick = viewModel::fetchPin, enabled = !state.busy) {
                            if (state.fetchingPin) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.node_fetch_pin))
                        }
                    }
                }
            }

            Text(stringResource(R.string.node_section_sync), style = MaterialTheme.typography.titleSmall)
            SelectField(
                label = stringResource(R.string.node_field_outbound),
                selected = state.outboundTag,
                options = listOf("") + state.outboundOptions.filter { it.isNotBlank() } +
                    listOfNotNull(state.outboundTag.takeIf { it.isNotBlank() && it !in state.outboundOptions }),
                text = { it.ifEmpty { stringResource(R.string.node_outbound_direct) } },
                onSelect = viewModel::onOutboundTag,
            )
            Text(stringResource(R.string.node_field_outbound_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectField(
                label = stringResource(R.string.node_field_sync),
                selected = state.inboundSyncMode,
                options = listOf("all", "selected"),
                text = { stringResource(if (it == "selected") R.string.node_sync_selected else R.string.node_sync_all) },
                onSelect = viewModel::onSyncMode,
            )
            if (state.inboundSyncMode == "selected") {
                OutlinedButton(onClick = viewModel::loadInbounds, enabled = !state.busy) {
                    if (state.fetchingInbounds) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.node_load_inbounds))
                }
                if (state.inboundOptions.isEmpty()) {
                    Text(stringResource(R.string.node_inbounds_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.inboundOptions.forEach { inbound ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = inbound.tag in state.inboundTags, onCheckedChange = { viewModel.onToggleTag(inbound.tag) })
                        Text(
                            buildString {
                                append(inbound.remark.ifBlank { inbound.tag })
                                if (inbound.protocol.isNotBlank()) append(" (${inbound.protocol}:${inbound.port})")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            state.testResult?.let { TestResultCard(it) }
            state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = viewModel::test, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    if (state.testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.node_test))
                }
                Button(onClick = viewModel::save, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.save))
                }
            }
        }
    }
}

private fun tlsLabel(mode: String): Int = when (mode) {
    "pin" -> R.string.node_tls_pin
    "skip" -> R.string.node_tls_skip
    "mtls" -> R.string.node_tls_mtls
    else -> R.string.node_tls_verify
}

@Composable
private fun TestResultCard(result: NodeTestResult) {
    val ok = result.online
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (ok) LocalStatusColors.current.success else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    if (ok) stringResource(R.string.node_test_ok, result.latencyMs) else stringResource(R.string.node_test_failed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ok && result.xrayVersion.isNotBlank()) Text("Xray ${result.xrayVersion}", style = MaterialTheme.typography.bodySmall)
                if (result.xrayError.isNotBlank()) Text(result.xrayError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!ok && result.error.isNotBlank()) Text(result.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: ValidationField? = null,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error?.let { stringResource(it.messageRes()) } ?: hint)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        textStyle = if (monospace) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TokenInput(value: String, onChange: (String) -> Unit, hint: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.node_field_token)) },
        supportingText = { Text(hint) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.hide else R.string.show)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
