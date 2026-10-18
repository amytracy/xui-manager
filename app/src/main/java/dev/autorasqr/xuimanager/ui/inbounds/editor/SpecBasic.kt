package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.data.inbound.editor.EditorDefaults
import dev.autorasqr.xuimanager.data.inbound.editor.InboundCapabilities
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.long
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.ui.common.SelectField
import java.text.DateFormat
import java.util.Date

internal fun p(path: String) = JsonPath(path)
internal fun opts(vararg values: String) = values.map { Opt(it) }
internal fun opts(values: List<String>) = values.map { Opt(it) }
internal fun InboundDraft.s(path: String) = this[p(path)].str().orEmpty()

private const val GB = 1024L * 1024 * 1024

/** Basic tab (InboundFormModal basicTab). */
object SpecBasic {
    fun fields(): List<Field> = listOf(
        SwitchField(p("enable"), Txt("Enabled", "Включён"), default = true, visible = { it.isNew }),
        TextField(p("remark"), Txt("Remark", "Название")),
        CustomField(visible = { it.isNew && it.protocol in EditorDefaults.NODE_PROTOCOLS }) { scope -> NodePicker(scope) },
        CustomField(visible = { !it.isNew && it.nodeId != null }) { scope -> NodeInfo(scope) },
        ChoiceField(
            path = p("protocol"),
            label = Txt("Protocol", "Протокол"),
            options = { opts(EditorDefaults.PROTOCOLS) },
            hint = Txt("Can't be changed after the inbound is created.", "После создания инбаунда протокол не меняется."),
            onSelect = { d, value -> d.withProtocol(value) },
            enabled = { it.isNew },
        ),
        TextField(
            p("listen"), Txt("Listen address", "Адрес прослушивания"), placeholder = "0.0.0.0",
            hint = Txt("Empty listens on all addresses; a path such as /dev/shm/x.sock makes a unix socket.", "Пусто — все адреса; путь вида /dev/shm/x.sock — unix-сокет."),
        ),
        NumberField(p("port"), Txt("Port", "Порт"), min = 0, max = 65535),
        ChoiceField(
            path = p("shareAddrStrategy"),
            label = Txt("Address in links", "Адрес в ссылках"),
            // "node" (the hosting node's address) only means something for an inbound placed on a node.
            options = { d -> if (d.nodeId != null) opts("node", "listen", "custom") else opts("listen", "custom") },
            hint = Txt("Which address client links and subscriptions advertise.", "Какой адрес попадает в ссылки и подписки клиентов."),
            default = "listen",
            visible = { it.protocol != "mtproto" },
        ),
        TextField(
            p("shareAddr"), Txt("Custom address", "Свой адрес"), placeholder = "edge.example.com",
            visible = { it.protocol != "mtproto" && it.s("shareAddrStrategy") == "custom" },
        ),
        NumberField(
            p("subSortIndex"), Txt("Order in subscriptions", "Порядок в подписке"),
            hint = Txt("Lower first; negatives allowed.", "Меньше — выше; можно отрицательные."),
        ),
        SwitchField(
            p("excludeFromSub"), Txt("Hide from subscriptions", "Скрыть из подписок"),
            hint = Txt("The inbound keeps working but its links are not served.", "Инбаунд работает, но его ссылки не отдаются."),
        ),
        SwitchField(
            p("disableFlow"), Txt("Disable flow", "Отключить flow"),
            hint = Txt("Leaves XTLS Vision flow out of client links.", "Не добавлять XTLS Vision flow в ссылки клиентов."),
            visible = { it.protocol == "vless" },
        ),
        CustomField { scope -> TotalTrafficField(scope) },
        ChoiceField(
            p("trafficReset"), Txt("Traffic reset", "Сброс трафика"),
            listOf(
                Opt("never", "never"), Opt("hourly", "hourly"), Opt("daily", "daily"),
                Opt("weekly", "weekly"), Opt("monthly", "monthly"),
            ),
            default = "never",
        ),
        NumberField(p("trafficResetDay"), Txt("Reset day of month", "День сброса"), min = 1, max = 31, visible = { it.s("trafficReset") == "monthly" }),
        CustomField { scope -> ExpiryField(scope) },
    )

    /** "Deploy to": the panel's own server or a node that answers; fixed once the inbound exists. */
    @Composable
    private fun NodePicker(scope: EditorScope) {
        val nodes = scope.nodes
        if (nodes.isEmpty()) return
        val current = scope.draft.nodeId
        val options = listOf<NodeChoice?>(null) + nodes.filter { it.online || it.id == current }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectField(
                label = Txt("Place on", "Разместить на").text(),
                selected = nodes.firstOrNull { it.id == current },
                options = options,
                text = { choice ->
                    choice?.name ?: Txt("This panel's server", "Сервер этой панели").text()
                },
                onSelect = { choice -> scope.update { it.withNode(choice?.id) } },
            )
            Text(
                Txt(
                    "Only nodes that answer are offered. The node runs the inbound; clients stay on this panel.",
                    "Предлагаются только отвечающие ноды. Инбаунд работает на ноде, клиенты остаются на этой панели.",
                ).text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun NodeInfo(scope: EditorScope) {
        val id = scope.draft.nodeId ?: return
        val name = scope.nodes.firstOrNull { it.id == id }?.name ?: "#$id"
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Txt("Placed on node", "Размещён на ноде").text(), style = MaterialTheme.typography.labelLarge)
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @Composable
    private fun TotalTrafficField(scope: EditorScope) {
        val bytes = scope.draft[p("total")].long() ?: 0
        var text by remember { mutableStateOf(if (bytes == 0L) "" else "%.2f".format(bytes.toDouble() / GB).trimEnd('0').trimEnd('.', ',')) }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isDigit() || it == '.' || it == ',' }
                val gb = text.replace(',', '.').toDoubleOrNull() ?: 0.0
                scope.update { it.set(p("total"), json((gb * GB).toLong())) }
            },
            label = { Text(Txt("Traffic limit, GB", "Лимит трафика, ГБ").text()) },
            supportingText = { Text(Txt("Empty or 0 means no limit.", "Пусто или 0 — без лимита.").text()) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ExpiryField(scope: EditorScope) {
        val expiry = scope.draft[p("expiryTime")].long() ?: 0
        var picking by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Txt("Expires", "Срок действия").text(), style = MaterialTheme.typography.labelLarge)
            Text(
                if (expiry > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiry)) else Txt("Never", "Бессрочно").text(),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }) { Text(Txt("Pick a date", "Выбрать дату").text()) }
                if (expiry > 0) TextButton(onClick = { scope.update { it.set(p("expiryTime"), json(0)) } }) { Text(Txt("Never expire", "Без срока").text()) }
            }
        }
        if (picking) {
            val state = rememberDatePickerState(initialSelectedDateMillis = expiry.takeIf { it > 0 } ?: (System.currentTimeMillis() + 30L * 86_400_000))
            DatePickerDialog(
                onDismissRequest = { picking = false },
                confirmButton = {
                    TextButton(onClick = {
                        // End of the chosen day, as the panel's date picker stores it.
                        state.selectedDateMillis?.let { day -> scope.update { it.set(p("expiryTime"), json(day + 86_399_000)) } }
                        picking = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = false }) { Text(Txt("Cancel", "Отмена").text()) } },
            ) { DatePicker(state = state) }
        }
    }
}

/** Sniffing tab (SniffingFields). */
object SpecSniffing {
    fun fields(): List<Field> {
        val on: Visible = { it[p("sniffing.enabled")].str() == "true" }
        return listOf(
            SwitchField(p("sniffing.enabled"), Txt("Sniffing", "Sniffing"), hint = Txt("Read the destination from the traffic itself.", "Определять адрес назначения по самому трафику.")),
            MultiChoiceField(p("sniffing.destOverride"), Txt("Override destination with", "Подменять адрес по"), opts("http", "tls", "quic", "fakedns"), visible = on),
            SwitchField(p("sniffing.metadataOnly"), Txt("Metadata only", "Только метаданные"), visible = on),
            SwitchField(p("sniffing.routeOnly"), Txt("Route only", "Только для маршрутизации"), visible = on),
            TagsField(p("sniffing.ipsExcluded"), Txt("Excluded IPs", "Исключённые IP"), placeholder = "IP/CIDR/geoip:*/ext:*", visible = on),
            TagsField(p("sniffing.domainsExcluded"), Txt("Excluded domains", "Исключённые домены"), placeholder = "domain:*/ext:*", visible = on),
        )
    }
}

/** Advanced tab: the panel's own raw editors for each part. */
object SpecAdvanced {
    fun fields(): List<Field> = listOf(
        JsonEditorField(
            p("settings"), Txt("settings"),
            hint = Txt("Protocol settings. Clients are managed on the Clients screen.", "Настройки протокола. Клиенты — на экране «Клиенты»."),
            emptyValue = kotlinx.serialization.json.JsonObject(emptyMap()),
        ),
        JsonEditorField(p("streamSettings"), Txt("streamSettings"), visible = InboundCapabilities::canEnableStream),
        JsonEditorField(p("sniffing"), Txt("sniffing"), visible = InboundCapabilities::canEnableSniffing),
    )
}
