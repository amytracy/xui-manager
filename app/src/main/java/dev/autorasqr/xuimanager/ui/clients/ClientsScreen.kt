package dev.autorasqr.xuimanager.ui.clients

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.Client
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.Clipboard
import dev.autorasqr.xuimanager.ui.common.Formatters
import dev.autorasqr.xuimanager.ui.common.QrCode
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.common.label
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.common.messageRes
import dev.autorasqr.xuimanager.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    /** Null when the screen is a tab of the server screen. */
    onBack: (() -> Unit)?,
    viewModel: ClientsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = message.error?.message(context) ?: message.text?.let(context::getString)
        viewModel.consumeMessage()
        if (text != null) snackbar.showSnackbar(text)
    }

    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(stringResource(R.string.clients_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
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
            if (state.snapshot?.inbounds?.isNotEmpty() == true) {
                FloatingActionButton(onClick = viewModel::openCreate) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.client_add))
                }
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
            when {
                snapshot == null && state.error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                snapshot == null -> ErrorState(state.error!!.message(), onRetry = viewModel::refresh)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.error?.let { error ->
                        item { Text(error.message(), color = MaterialTheme.colorScheme.error) }
                    }
                    if (snapshot.inbounds.isEmpty()) {
                        item { EmptyText(stringResource(R.string.error_no_inbound)) }
                    } else if (snapshot.clients.isEmpty()) {
                        item { EmptyText(stringResource(R.string.clients_empty)) }
                    }
                    items(snapshot.clients, key = { it.email }) { client ->
                        ClientRow(client) { viewModel.openSubscription(client) }
                    }
                }
            }
        }
    }

    state.form?.let { form ->
        ClientFormDialog(
            form = form,
            inbounds = state.snapshot?.inbounds.orEmpty(),
            multiInbound = state.snapshot?.multiInbound ?: false,
            viewModel = viewModel,
        )
    }

    state.subscription?.let { sheet ->
        ClientSheet(
            sheet = sheet,
            onDismiss = viewModel::closeSubscription,
            onEdit = { viewModel.openEdit(sheet.client) },
            onToggle = { viewModel.setEnabled(sheet.client, !sheet.client.enabled) },
            onReset = { viewModel.requestConfirmation(ClientAction.RESET_TRAFFIC, sheet.client) },
            onDelete = { viewModel.requestConfirmation(ClientAction.DELETE, sheet.client) },
        )
    }

    state.confirmation?.let { pending ->
        val delete = pending.action == ClientAction.DELETE
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(if (delete) R.string.client_delete_title else R.string.client_reset_title)) },
            text = { Text(stringResource(if (delete) R.string.client_delete_text else R.string.client_reset_text, pending.email)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirm) {
                    Text(
                        stringResource(if (delete) R.string.delete else R.string.client_reset),
                        color = if (delete) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ClientRow(client: Client, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (client.online) {
                    Box(Modifier.size(8.dp).background(LocalStatusColors.current.online, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(client.email, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when {
                    !client.enabled -> Text(
                        stringResource(R.string.client_disabled),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    client.online -> Text(
                        stringResource(R.string.client_online),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalStatusColors.current.online,
                    )
                }
            }
            val used = Formatters.bytes(context, client.usedBytes)
            Text(
                if (client.totalBytes > 0) stringResource(R.string.traffic_of, used, Formatters.bytes(context, client.totalBytes))
                else stringResource(R.string.traffic_unlimited, used),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (client.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (client.usedBytes.toFloat() / client.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = expiryText(client.expiryTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun expiryText(expiryTime: Long): String = when {
    expiryTime == 0L -> stringResource(R.string.expiry_never)
    expiryTime < 0 -> (-expiryTime / Formatters.DAY_MS).toInt().let { pluralStringResource(R.plurals.expiry_delayed, it, it) }
    expiryTime < System.currentTimeMillis() -> stringResource(R.string.expired, Formatters.date(expiryTime))
    else -> stringResource(R.string.expiry_until, Formatters.date(expiryTime))
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun ClientFormDialog(
    form: ClientForm,
    inbounds: List<InboundSummary>,
    multiInbound: Boolean,
    viewModel: ClientsViewModel,
) {
    AlertDialog(
        onDismissRequest = { if (!form.busy) viewModel.closeForm() },
        title = { Text(stringResource(if (form.isEdit) R.string.client_edit else R.string.client_add)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormField(
                    value = form.email,
                    onChange = viewModel::onEmail,
                    label = stringResource(R.string.field_email),
                    error = form.fieldError.takeIf { it == ValidationField.EMAIL || it == ValidationField.EMAIL_TAKEN },
                    placeholder = stringResource(R.string.field_email_hint),
                    // Email keyboards skip autocorrect and auto-capitalisation, which would mangle "anna-phone".
                    keyboardType = KeyboardType.Email,
                )
                FormField(
                    value = form.trafficGb,
                    onChange = viewModel::onTraffic,
                    label = stringResource(R.string.field_traffic),
                    error = form.fieldError.takeIf { it == ValidationField.TRAFFIC },
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = form.days,
                    onChange = viewModel::onDays,
                    label = stringResource(R.string.field_expiry_days),
                    error = form.fieldError.takeIf { it == ValidationField.EXPIRY },
                    keyboardType = KeyboardType.Number,
                    hint = if (form.isEdit && form.originalExpiry < 0 && !form.daysTouched) stringResource(R.string.expiry_delayed_hint) else null,
                )
                FormField(
                    value = form.subId,
                    onChange = viewModel::onSubId,
                    label = stringResource(R.string.field_sub_id),
                    error = form.fieldError.takeIf { it == ValidationField.SUB_ID },
                    keyboardType = KeyboardType.Ascii,
                    monospace = true,
                )
                TextButton(onClick = viewModel::regenerateSubId) { Text(stringResource(R.string.regenerate)) }
                if (form.isEdit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.client_enabled_switch), modifier = Modifier.weight(1f))
                        Switch(checked = form.enabled, onCheckedChange = viewModel::onEnabled)
                    }
                }
                if (inbounds.size > 1 || form.fieldError == ValidationField.INBOUNDS) {
                    Text(
                        stringResource(if (multiInbound) R.string.client_inbounds_multi else R.string.client_inbounds_single),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    inbounds.forEach { inbound ->
                        InboundOption(
                            inbound = inbound,
                            selected = inbound.id in form.inboundIds,
                            multi = multiInbound,
                            enabled = multiInbound || !form.isEdit,
                            onClick = { viewModel.onInboundToggled(inbound.id) },
                        )
                    }
                    if (form.fieldError == ValidationField.INBOUNDS) {
                        Text(stringResource(ValidationField.INBOUNDS.messageRes()), color = MaterialTheme.colorScheme.error)
                    }
                    if (!multiInbound && form.isEdit) {
                        Text(
                            stringResource(R.string.client_inbound_fixed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                form.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveForm, enabled = !form.busy) {
                if (form.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (form.isEdit) R.string.save else R.string.create))
                }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeForm, enabled = !form.busy) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun InboundOption(inbound: InboundSummary, selected: Boolean, multi: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        if (multi) {
            Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled)
        } else {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
        Text(inbound.label(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: ValidationField?,
    placeholder: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = when {
            error != null -> { { Text(stringResource(error.messageRes())) } }
            hint != null -> { { Text(hint) } }
            else -> null
        },
        singleLine = true,
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientSheet(
    sheet: SubscriptionSheet,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    SecureScreen()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.copied)
    val shareTitle = stringResource(R.string.share_subscription)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.subscription_title, sheet.client.email),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            when {
                sheet.error != null -> Text(sheet.error.message(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp))
                sheet.url == null -> CircularProgressIndicator()
                else -> {
                    QrCode(sheet.url)
                    SelectionContainer(Modifier.padding(horizontal = 24.dp)) {
                        Text(sheet.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    if (sheet.url.startsWith("http://")) {
                        Text(
                            stringResource(R.string.subscription_http_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalStatusColors.current.warning,
                        )
                    }
                    Text(
                        stringResource(R.string.subscription_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            copySensitive(context, sheet.url)
                            // Android 13+ shows its own clipboard confirmation.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.copy)) }
                        Button(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sheet.url)
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.share))
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth()) {
                SheetAction(Icons.Default.Edit, stringResource(R.string.client_edit), onClick = onEdit)
                SheetAction(
                    icon = if (sheet.client.enabled) Icons.Default.Close else Icons.Default.CheckCircle,
                    text = stringResource(if (sheet.client.enabled) R.string.client_disable else R.string.client_enable),
                    onClick = onToggle,
                )
                SheetAction(Icons.Default.Refresh, stringResource(R.string.client_reset), onClick = onReset)
                SheetAction(Icons.Default.Delete, stringResource(R.string.delete), destructive = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(text, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun copySensitive(context: Context, text: String) = Clipboard.copy(context, "subscription", text, sensitive = true)
