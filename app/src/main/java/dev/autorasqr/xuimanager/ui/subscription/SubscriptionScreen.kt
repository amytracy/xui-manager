package dev.autorasqr.xuimanager.ui.subscription

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.domain.model.SubBalancer
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.SelectField
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.common.label
import dev.autorasqr.xuimanager.ui.common.message
import dev.autorasqr.xuimanager.ui.inbounds.editor.EditorScope
import dev.autorasqr.xuimanager.ui.inbounds.editor.FallbackRow
import dev.autorasqr.xuimanager.ui.inbounds.editor.FieldList
import dev.autorasqr.xuimanager.ui.inbounds.editor.Opt
import dev.autorasqr.xuimanager.ui.inbounds.editor.RemoteActionId
import dev.autorasqr.xuimanager.ui.inbounds.editor.ScanSummary
import dev.autorasqr.xuimanager.ui.inbounds.editor.Txt

/** The panel's subscription settings (3x-ui v3), field by field as in its own Settings page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val ru = LocalConfiguration.current.locales[0].language == "ru"
    val savedText = Txt("Saved; the panel has restarted.", "Сохранено, панель перезапущена.").text()

    LaunchedEffect(ui.saved) { if (ui.saved) snackbar.showSnackbar(savedText) }
    BackHandler(enabled = ui.dirty && !ui.saving) { confirmLeave = true }

    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(Txt("Subscription settings", "Настройки подписки").text()) },
                navigationIcon = {
                    IconButton(onClick = { if (ui.dirty) confirmLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (ui.draft != null) {
                        if (ui.saving) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        TextButton(onClick = viewModel::save, enabled = ui.dirty && !ui.saving) { Text(Txt("Save", "Сохранить").text()) }
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
                        "Subscription settings need 3x-ui v3. Use the panel's web interface for this server.",
                        "Настройки подписки работают с 3x-ui v3. Для этого сервера используйте веб-интерфейс панели.",
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
                val tabs = SubTab.entries
                PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(ui.tab), edgePadding = 8.dp) {
                    tabs.forEach { tab ->
                        Tab(selected = tab == ui.tab, onClick = { viewModel.selectTab(tab) }, text = { Text(tabTitle(tab).text()) })
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
                    if (ui.saving) {
                        Text(
                            Txt("Saving and restarting the panel…", "Сохранение и перезапуск панели…").text(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ui.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                    if (ui.tab == SubTab.BALANCERS) BalancersList(ui, viewModel)
                    FieldList(fieldsFor(ui.tab, draft, ui.known, ru), SettingsScope(draft, ui.saving, viewModel))
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    ui.editingBalancer?.let { balancer ->
        BalancerDialog(balancer, ui.balancerInbounds, ui.balancerBusy, viewModel)
    }
    ui.confirmDeleteBalancer?.let { balancer ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteBalancer,
            title = { Text(Txt("Delete this balancer?", "Удалить этот балансировщик?").text()) },
            text = { Text(balancer.remark) },
            confirmButton = {
                TextButton(onClick = viewModel::deleteBalancer) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteBalancer) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(Txt("Discard changes?", "Отменить изменения?").text()) },
            text = { Text(Txt("The subscription settings have unsaved changes.", "В настройках подписки есть несохранённые изменения.").text()) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) {
                    Text(Txt("Discard", "Отменить").text(), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(Txt("Keep editing", "Продолжить").text()) } },
        )
    }
}

@Composable
private fun BalancersList(ui: SubscriptionUiState, viewModel: SubscriptionViewModel) {
    Text(
        Txt(
            "Each enabled balancer is one extra profile in the JSON subscription that picks the best of the chosen inbounds. Balancers are saved right away.",
            "Каждый включённый балансировщик — отдельный профиль JSON-подписки, который выбирает лучший из выбранных инбаундов. Балансировщики сохраняются сразу.",
        ).text(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ui.balancerError?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
    if (ui.balancers.isEmpty()) {
        Text(Txt("No balancers yet", "Балансировщиков пока нет").text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ui.balancers.forEach { balancer ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${balancer.sortOrder}. ${balancer.remark}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            strategyLabel(balancer.strategy).text() + " · " +
                                Txt("${balancer.inboundIds.size} inbounds", "инбаундов: ${balancer.inboundIds.size}").text(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = balancer.enabled, onCheckedChange = { viewModel.setBalancerEnabled(balancer, it) }, enabled = !ui.balancerBusy)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { viewModel.editBalancer(balancer) }, enabled = !ui.balancerBusy) { Text(stringResource(R.string.edit)) }
                    IconButton(onClick = { viewModel.requestDeleteBalancer(balancer) }, enabled = !ui.balancerBusy) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = viewModel::newBalancer, enabled = !ui.balancerBusy, modifier = Modifier.fillMaxWidth()) {
        Text(Txt("Add balancer", "Добавить балансировщик").text())
    }
}

private val STRATEGIES = listOf("random", "roundRobin", "leastPing", "leastLoad")

private fun strategyLabel(strategy: String) = when (strategy) {
    "leastLoad" -> Txt("Least load", "Минимальная нагрузка")
    "leastPing" -> Txt("Least ping", "Минимальный пинг")
    "roundRobin" -> Txt("Round robin", "По очереди")
    "random" -> Txt("Random", "Случайный")
    else -> Txt(strategy)
}

@Composable
private fun BalancerDialog(balancer: SubBalancer, inbounds: List<InboundSummary>, busy: Boolean, viewModel: SubscriptionViewModel) {
    val valid = balancer.remark.isNotBlank() && balancer.inboundIds.isNotEmpty() && balancer.sortOrder >= 1
    AlertDialog(
        onDismissRequest = viewModel::closeBalancer,
        title = { Text(if (balancer.id == 0) Txt("Add balancer", "Добавить балансировщик").text() else Txt("Balancer", "Балансировщик").text()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = balancer.remark,
                    onValueChange = { viewModel.changeBalancer(balancer.copy(remark = it)) },
                    label = { Text(Txt("Remark", "Примечание").text()) },
                    placeholder = { Text(Txt("Auto · fastest", "Авто · самый быстрый").text()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectField(
                    label = Txt("Strategy", "Стратегия").text(),
                    selected = balancer.strategy,
                    options = STRATEGIES,
                    text = { strategyLabel(it).text() },
                    onSelect = { viewModel.changeBalancer(balancer.copy(strategy = it)) },
                )
                OutlinedTextField(
                    value = balancer.sortOrder.toString(),
                    onValueChange = { text -> text.filter(Char::isDigit).toIntOrNull()?.let { viewModel.changeBalancer(balancer.copy(sortOrder = it)) } },
                    label = { Text(Txt("Order", "Порядок").text()) },
                    supportingText = {
                        Text(Txt("Position in the subscription list among the inbounds.", "Место в списке подписки среди инбаундов.").text())
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(Txt("Inbounds", "Инбаунды").text(), style = MaterialTheme.typography.labelLarge)
                inbounds.forEach { inbound ->
                    val checked = inbound.id in balancer.inboundIds
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                val ids = if (on) balancer.inboundIds + inbound.id else balancer.inboundIds - inbound.id
                                viewModel.changeBalancer(balancer.copy(inboundIds = ids, memberWeights = balancer.memberWeights.filterKeys { it in ids }))
                            },
                        )
                        Text(inbound.label(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                if (balancer.strategy == "leastLoad" && balancer.inboundIds.isNotEmpty()) {
                    Text(Txt("Member weights", "Веса участников").text(), style = MaterialTheme.typography.labelLarge)
                    Text(
                        Txt("A lower weight is picked more often; members without a value weigh 1.", "Чем меньше вес, тем чаще выбирается участник; без значения вес 1.").text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    balancer.inboundIds.forEach { id ->
                        WeightRow(
                            label = inbounds.firstOrNull { it.id == id }?.let { "#${it.id} ${it.remark}" } ?: "#$id",
                            weight = balancer.memberWeights[id],
                            onChange = { w ->
                                val weights = if (w == null) balancer.memberWeights - id else balancer.memberWeights + (id to w)
                                viewModel.changeBalancer(balancer.copy(memberWeights = weights))
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveBalancer, enabled = valid && !busy) {
                Text(if (balancer.id == 0) Txt("Create", "Создать").text() else Txt("Save", "Сохранить").text())
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeBalancer) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun WeightRow(label: String, weight: Double?, onChange: (Double?) -> Unit) {
    var text by remember(label) { mutableStateOf(weight?.toString().orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                onChange(value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 })
            },
            placeholder = { Text("1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** The settings draft seen through the inbound editor's widgets; nothing here talks to the panel on its own. */
private class SettingsScope(
    override val draft: InboundDraft,
    override val busy: Boolean,
    private val viewModel: SubscriptionViewModel,
) : EditorScope {
    override fun update(transform: (InboundDraft) -> InboundDraft) = viewModel.update(transform)
    override fun remote(id: RemoteActionId, arg: Any?) = Unit
    override val fallbacks: List<FallbackRow> get() = emptyList()
    override val fallbackChildren: List<Opt> get() = emptyList()
    override fun updateFallbacks(rows: List<FallbackRow>) = Unit
    override val scanResult: ScanSummary? get() = null
    override val nodes: List<NodeChoice> get() = emptyList()
}
