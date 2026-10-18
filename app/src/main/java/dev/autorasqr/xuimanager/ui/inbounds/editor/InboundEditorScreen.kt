package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.inbound.editor.EditorIssue
import dev.autorasqr.xuimanager.data.inbound.editor.EditorTab
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.ui.common.message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboundEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: InboundEditorViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val ru = LocalConfiguration.current.locales[0].language == "ru"

    LaunchedEffect(ui.saved) { if (ui.saved) onSaved() }
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        viewModel.consumeNotice()
        snackbar.showSnackbar(if (ru) notice.ru else notice.en)
    }
    BackHandler(enabled = ui.dirty && !ui.saved) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isNew) Txt("New inbound", "Новый инбаунд").text()
                        else Txt("Inbound", "Инбаунд").text() + (ui.draft?.s("remark")?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (ui.dirty) confirmLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (ui.draft != null) {
                        if (ui.saving || ui.busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        TextButton(onClick = viewModel::save, enabled = !ui.saving) {
                            Text(if (viewModel.isNew) Txt("Create", "Создать").text() else Txt("Save", "Сохранить").text())
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val draft = ui.draft
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.unsupported -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    Txt(
                        "The full editor needs 3x-ui v3. Use the panel's web interface for this server.",
                        "Полный редактор работает с 3x-ui v3. Для этого сервера используйте веб-интерфейс панели.",
                    ).text(),
                    textAlign = TextAlign.Center,
                )
            }
            draft == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ui.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                val tabs = visibleTabs(draft)
                val selected = if (ui.tab in tabs) ui.tab else EditorTab.BASIC
                PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(selected), edgePadding = 8.dp) {
                    tabs.forEach { tab ->
                        Tab(selected = tab == selected, onClick = { viewModel.selectTab(tab) }, text = { Text(tabTitle(tab).text()) })
                    }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ui.issue?.let { IssueCard(it) }
                    ui.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                    FieldList(fieldsFor(selected), ScreenScope(ui, draft, viewModel))
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(Txt("Discard changes?", "Отменить изменения?").text()) },
            text = { Text(Txt("The inbound has unsaved changes.", "В инбаунде есть несохранённые изменения.").text()) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) {
                    Text(Txt("Discard", "Отменить").text(), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(Txt("Keep editing", "Продолжить").text()) } },
        )
    }
}

private fun visibleTabs(d: InboundDraft): List<EditorTab> = buildList {
    add(EditorTab.BASIC)
    if (InboundCapabilities.showProtocolTab(d)) add(EditorTab.PROTOCOL)
    if (InboundCapabilities.canEnableStream(d)) add(EditorTab.STREAM)
    if (InboundCapabilities.showSecurityTab(d)) add(EditorTab.SECURITY)
    if (InboundCapabilities.canEnableSniffing(d)) add(EditorTab.SNIFFING)
    add(EditorTab.ADVANCED)
}

private fun tabTitle(tab: EditorTab) = when (tab) {
    EditorTab.BASIC -> Txt("Basic", "Основное")
    EditorTab.PROTOCOL -> Txt("Protocol", "Протокол")
    EditorTab.STREAM -> Txt("Transport", "Транспорт")
    EditorTab.SECURITY -> Txt("Security", "Безопасность")
    EditorTab.SNIFFING -> Txt("Sniffing")
    EditorTab.ADVANCED -> Txt("JSON")
}

private val TAB_FIELDS: Map<EditorTab, List<Field>> by lazy {
    mapOf(
        EditorTab.BASIC to SpecBasic.fields(),
        EditorTab.PROTOCOL to SpecProtocol.fields(),
        EditorTab.STREAM to SpecStream.fields(),
        EditorTab.SECURITY to SpecSecurity.fields(),
        EditorTab.SNIFFING to SpecSniffing.fields(),
        EditorTab.ADVANCED to SpecAdvanced.fields(),
    )
}

private fun fieldsFor(tab: EditorTab): List<Field> = TAB_FIELDS.getValue(tab)

@Composable
private fun IssueCard(issue: EditorIssue) {
    val text = when (issue.code) {
        "port" -> Txt("Port must be between 1 and 65535 (0 only for a unix socket).", "Порт — от 1 до 65535 (0 только для unix-сокета).")
        "trafficResetDay" -> Txt("The reset day must be 1–31.", "День сброса — от 1 до 31.")
        "required" -> Txt("Fill in ${issue.detail}.", "Заполните ${issue.detail}.")
        "accounts" -> Txt("Every account needs a user and a password.", "У каждой учётной записи должны быть пользователь и пароль.")
        "tlsServerCert" -> Txt("TLS needs at least one certificate that is not just for verification.", "Для TLS нужен хотя бы один сертификат не только для проверки (verify).")
        "tlsCert" -> Txt("Certificate #${issue.detail}: the certificate is missing.", "Сертификат №${issue.detail}: не указан сертификат.")
        "tlsKey" -> Txt("Certificate #${issue.detail}: the private key is missing.", "Сертификат №${issue.detail}: не указан приватный ключ.")
        "realityTarget" -> Txt("Reality target must be host:port, a port or a socket path.", "Цель Reality — host:port, порт или путь к сокету.")
        "clientVer" -> Txt("${issue.detail}: use x.y.z with numbers 0–255.", "${issue.detail}: формат x.y.z, числа 0–255.")
        "adTag" -> Txt("The ad tag must be 32 hex characters.", "Ad tag — 32 шестнадцатеричных символа.")
        else -> Txt(issue.code)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Text(text.text(), color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
    }
}

/** A fresh scope per state, so every field recomposes with the current draft. */
private class ScreenScope(
    private val ui: EditorUiState,
    override val draft: InboundDraft,
    private val viewModel: InboundEditorViewModel,
) : EditorScope {
    override val busy: Boolean get() = ui.busy
    override fun update(transform: (InboundDraft) -> InboundDraft) = viewModel.update(transform)
    override fun remote(id: RemoteActionId, arg: Any?) = viewModel.remote(id, arg)
    override val fallbacks: List<FallbackRow> get() = ui.fallbacks
    override val fallbackChildren: List<Opt> get() = ui.fallbackChildren
    override fun updateFallbacks(rows: List<FallbackRow>) = viewModel.updateFallbacks(rows)
    override val scanResult: ScanSummary? get() = ui.scan
    override val nodes: List<NodeChoice> get() = ui.nodes
}
