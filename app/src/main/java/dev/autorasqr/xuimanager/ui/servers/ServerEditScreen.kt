package dev.autorasqr.xuimanager.ui.servers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.ui.common.HostKeyDialog
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.common.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    /** Null when this is the first screen (onboarding): there is nothing to go back to. */
    onBack: (() -> Unit)?,
    onCreated: (Long) -> Unit,
    onUpdated: () -> Unit,
    onDeleted: (wasNode: Boolean) -> Unit,
    /** Opens the screen that removes everything the app installed from the server, and then the server. */
    onUninstall: (Long) -> Unit,
    viewModel: ServerEditViewModel = hiltViewModel(),
) {
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadKeyFile(context.contentResolver, uri)
    }

    LaunchedEffect(state.result) {
        when (val result = state.result) {
            is ServerEditState.Result.Created -> onCreated(result.id)
            ServerEditState.Result.Updated -> onUpdated()
            is ServerEditState.Result.Deleted -> onDeleted(result.wasNode)
            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                state.serverId == null && state.masterServerId != null -> R.string.node_server_title_new
                                state.serverId == null -> R.string.server_edit_title_new
                                else -> R.string.server_edit_title_edit
                            },
                        ),
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (state.serverId != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
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
            if (state.serverId == null) {
                Text(
                    stringResource(
                        when {
                            state.restoreNodeId != null -> R.string.node_restore_intro
                            state.masterServerId != null -> R.string.node_server_intro
                            else -> R.string.onboarding_text
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.masterServerId == null) {
                    Text(
                        stringResource(R.string.onboarding_restore_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Field(
                value = state.name,
                onChange = viewModel::onName,
                label = R.string.field_name,
                isError = state.fieldError == ValidationField.NAME,
                errorField = ValidationField.NAME,
                modifier = Modifier.fillMaxWidth(),
            )
            Field(
                value = state.host,
                onChange = viewModel::onHost,
                label = R.string.field_host,
                isError = state.fieldError == ValidationField.HOST,
                errorField = ValidationField.HOST,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Uri,
            )
            Field(
                value = state.domain,
                onChange = viewModel::onDomain,
                label = R.string.field_domain,
                isError = state.fieldError == ValidationField.DOMAIN,
                errorField = ValidationField.DOMAIN,
                hint = R.string.field_domain_hint,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Uri,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(
                    value = state.port,
                    onChange = viewModel::onPort,
                    label = R.string.field_ssh_port,
                    isError = state.fieldError == ValidationField.PORT,
                    errorField = ValidationField.PORT,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.width(120.dp),
                )
                Field(
                    value = state.user,
                    onChange = viewModel::onUser,
                    label = R.string.field_user,
                    isError = state.fieldError == ValidationField.USER,
                    errorField = ValidationField.USER,
                    modifier = Modifier.weight(1f),
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SshAuthType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.authType == type,
                        onClick = { viewModel.onAuthType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, SshAuthType.entries.size),
                    ) {
                        Text(stringResource(if (type == SshAuthType.PASSWORD) R.string.auth_password else R.string.auth_key))
                    }
                }
            }

            when (state.authType) {
                SshAuthType.PASSWORD -> SecretField(
                    value = state.password,
                    onChange = viewModel::onPassword,
                    label = R.string.field_password,
                    keepHint = state.hasStoredPassword,
                    isError = state.fieldError == ValidationField.PASSWORD,
                    errorField = ValidationField.PASSWORD,
                )
                SshAuthType.PRIVATE_KEY -> {
                    OutlinedTextField(
                        value = state.privateKey,
                        onValueChange = viewModel::onPrivateKey,
                        label = { Text(stringResource(R.string.field_private_key)) },
                        placeholder = if (state.hasStoredKey) {
                            { Text(stringResource(R.string.secret_keep_hint)) }
                        } else {
                            null
                        },
                        isError = state.fieldError == ValidationField.PRIVATE_KEY || state.keyFileError,
                        supportingText = when {
                            state.keyFileError -> { { Text(stringResource(R.string.key_file_error)) } }
                            state.fieldError == ValidationField.PRIVATE_KEY -> {
                                { Text(stringResource(ValidationField.PRIVATE_KEY.messageRes())) }
                            }
                            else -> null
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 4,
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.key_from_file))
                    }
                    SecretField(
                        value = state.passphrase,
                        onChange = viewModel::onPassphrase,
                        label = R.string.field_passphrase,
                        keepHint = false,
                        isError = false,
                        errorField = null,
                    )
                }
            }

            state.error?.let { error ->
                Text(error.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (state.offerSaveWithoutCheck) {
                    TextButton(onClick = viewModel::saveWithoutCheck) { Text(stringResource(R.string.save_without_check)) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = viewModel::save, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.server_checking))
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        }
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_server_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (state.masterServerId != null) R.string.delete_node_server_text else R.string.delete_server_text, state.name))
                    Text(stringResource(R.string.delete_from_server_text))
                    OutlinedButton(
                        onClick = {
                            confirmDelete = false
                            viewModel.delete()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.delete_from_app)) }
                    Button(
                        onClick = {
                            confirmDelete = false
                            state.serverId?.let(onUninstall)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.delete_from_server)) }
                }
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    isError: Boolean,
    errorField: ValidationField?,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = isError,
        supportingText = when {
            isError && errorField != null -> { { Text(stringResource(errorField.messageRes())) } }
            hint != null -> { { Text(stringResource(hint)) } }
            else -> null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = modifier,
    )
}

@Composable
private fun SecretField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    keepHint: Boolean,
    isError: Boolean,
    errorField: ValidationField?,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = if (keepHint) {
            { Text(stringResource(R.string.secret_keep_hint)) }
        } else {
            null
        },
        isError = isError,
        supportingText = if (isError && errorField != null) {
            { Text(stringResource(errorField.messageRes())) }
        } else {
            null
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.hide else R.string.show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
