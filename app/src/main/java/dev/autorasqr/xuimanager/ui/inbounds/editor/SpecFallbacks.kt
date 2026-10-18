package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.ui.common.SelectField

/** FallbacksCard: rows pointing at other inbounds (or a dest), matched by SNI, ALPN and path. */
@Composable
fun FallbacksCard(scope: EditorScope) {
    val rows = scope.fallbacks
    val none = Opt("", Txt("Custom dest", "Свой dest").text())
    val children = listOf(none) + scope.fallbackChildren
    fun replace(index: Int, row: FallbackRow) = scope.updateFallbacks(rows.mapIndexed { i, r -> if (i == index) row else r })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fallbacks", style = MaterialTheme.typography.titleSmall)
                Text(
                    Txt(
                        "Connections that are not clients go to these inbounds, chosen by SNI, ALPN and path. Saved together with the inbound.",
                        "Соединения, которые не являются клиентами, уходят на эти инбаунды по SNI, ALPN и пути. Сохраняются вместе с инбаундом.",
                    ).text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { scope.updateFallbacks(rows + FallbackRow()) }) { Icon(Icons.Default.Add, contentDescription = null) }
        }
        rows.forEachIndexed { index, row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { scope.updateFallbacks(rows.filterIndexed { i, _ -> i != index }) }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    SelectField(
                        label = Txt("Inbound", "Инбаунд").text(),
                        selected = children.firstOrNull { it.value == (row.childId?.toString() ?: "") } ?: none,
                        options = children,
                        text = { it.label },
                        onSelect = { opt -> replace(index, row.copy(childId = opt.value.toIntOrNull())) },
                    )
                    if (row.childId == null) {
                        Line("dest", row.dest, "8443 / 127.0.0.1:8443 / /dev/shm/x.sock") { replace(index, row.copy(dest = it)) }
                    }
                    Line("SNI", row.name, "example.com") { replace(index, row.copy(name = it)) }
                    Line("ALPN", row.alpn, "h2") { replace(index, row.copy(alpn = it)) }
                    Line(Txt("Path", "Путь").text(), row.path, "/ws") { replace(index, row.copy(path = it)) }
                    OutlinedTextField(
                        value = row.xver.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.takeIf { it in 0..2 }?.let { replace(index, row.copy(xver = it)) } },
                        label = { Text("xver (PROXY protocol)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
