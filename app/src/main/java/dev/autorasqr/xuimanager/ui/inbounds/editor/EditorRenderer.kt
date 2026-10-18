package dev.autorasqr.xuimanager.ui.inbounds.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.jsonStrings
import dev.autorasqr.xuimanager.data.inbound.editor.obj
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.data.inbound.editor.strings
import dev.autorasqr.xuimanager.ui.common.SelectField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun FieldList(fields: List<Field>, scope: EditorScope) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.filter { it.visible(scope.draft) }.forEach { RenderField(it, scope) }
    }
}

@Composable
private fun RenderField(field: Field, scope: EditorScope) {
    when (field) {
        is TextField -> TextFieldView(field, scope)
        is NumberField -> NumberFieldView(field, scope)
        is SwitchField -> SwitchFieldView(field, scope)
        is ChoiceField -> ChoiceFieldView(field, scope)
        is MultiChoiceField -> MultiChoiceView(field, scope)
        is TagsField -> TagsView(field, scope)
        is LinesField -> LinesView(field, scope)
        is MapField -> MapView(field, scope)
        is Section -> SectionView(field, scope)
        is ListField -> ListView(field, scope)
        is Actions -> ActionsView(field, scope)
        is InfoField -> InfoView(field, scope)
        is CustomField -> field.content(scope)
        is JsonEditorField -> JsonEditorView(field, scope)
    }
}

@Composable
private fun Hint(hint: Txt?) {
    if (hint != null) Text(hint.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TextFieldView(f: TextField, scope: EditorScope) {
    val value = scope.draft[f.path].let { (it as? JsonPrimitive)?.content }.orEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = { text -> scope.update { it.set(f.path, if (text.isBlank() && f.removeWhenBlank) null else json(text)) } },
        label = { Text(f.label.text()) },
        placeholder = f.placeholder?.let { { Text(it) } },
        supportingText = f.hint?.let { { Text(it.text()) } },
        singleLine = !f.multiline,
        minLines = if (f.multiline) 3 else 1,
        textStyle = if (f.monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberFieldView(f: NumberField, scope: EditorScope) {
    val stored = scope.draft[f.path].let { (it as? JsonPrimitive)?.content }.orEmpty()
    var text by remember(f.path) { mutableStateOf(stored) }
    LaunchedEffect(stored) { if (text.toLongOrNull()?.toString() != stored && text != stored) text = stored }
    val number = text.toLongOrNull()
    val error = when {
        text.isBlank() -> null
        f.asString -> null
        number == null -> "?"
        f.min != null && number < f.min -> "≥ ${f.min}"
        f.max != null && number > f.max -> "≤ ${f.max}"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val clean = if (f.asString) input.filter { it.isDigit() || it == '-' } else input.filter { it.isDigit() || (it == '-' && (f.min ?: 0) < 0) }
            text = clean
            scope.update { d ->
                when {
                    clean.isBlank() -> if (f.removeWhenBlank) d.set(f.path, null) else d
                    f.asString -> d.set(f.path, clean.toLongOrNull()?.let(::json) ?: json(clean))
                    else -> clean.toLongOrNull()?.let { d.set(f.path, json(it)) } ?: d
                }
            }
        },
        label = { Text(f.label.text()) },
        placeholder = f.placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: f.hint?.text())?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (f.asString) KeyboardType.Ascii else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchFieldView(f: SwitchField, scope: EditorScope) {
    val checked = (scope.draft[f.path] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: f.default
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(f.label.text(), style = MaterialTheme.typography.bodyLarge)
            Hint(f.hint)
        }
        Switch(checked = checked, onCheckedChange = { on -> scope.update { it.set(f.path, if (!on && f.removeWhenOff) null else json(on)) } })
    }
}

@Composable
private fun ChoiceFieldView(f: ChoiceField, scope: EditorScope) {
    val current = scope.draft[f.path].str() ?: f.default
    val options = f.options(scope.draft)
    val selected = options.firstOrNull { it.value == current } ?: Opt(current, current.ifEmpty { "—" })
    Column {
        if (f.enabled(scope.draft)) {
            SelectField(
                label = f.label.text(),
                selected = selected,
                options = options,
                text = { it.label },
                onSelect = { opt ->
                    scope.update { d ->
                        f.onSelect?.invoke(d, opt.value) ?: d.set(f.path, if (opt.value.isEmpty() && f.removeWhenEmpty) null else json(opt.value))
                    }
                },
            )
        } else {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                enabled = false,
                label = { Text(f.label.text()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hint(f.hint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiChoiceView(f: MultiChoiceField, scope: EditorScope) {
    val selected = scope.draft[f.path].strings()
    Column {
        Text(f.label.text(), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            f.options.forEach { opt ->
                FilterChip(
                    selected = opt.value in selected,
                    onClick = {
                        val next = if (opt.value in selected) selected - opt.value else f.options.map { it.value }.filter { it in selected || it == opt.value }
                        scope.update { it.set(f.path, jsonStrings(next)) }
                    },
                    label = { Text(opt.label) },
                )
            }
        }
        Hint(f.hint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsView(f: TagsField, scope: EditorScope) {
    val raw = scope.draft[f.path]
    val values = if (f.joined) raw.str().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } else raw.strings()
    var input by remember(f.path) { mutableStateOf("") }
    fun write(next: List<String>) = scope.update { d ->
        when {
            next.isEmpty() && f.removeWhenEmpty -> d.set(f.path, null)
            f.joined -> d.set(f.path, json(next.joinToString(",")))
            else -> d.set(f.path, jsonStrings(next))
        }
    }
    fun commit() {
        val parts = input.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() && it !in values }
        if (parts.isNotEmpty()) write(values + parts)
        input = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(f.label.text(), style = MaterialTheme.typography.labelLarge)
        if (values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                values.forEach { v ->
                    // A tap removes the value.
                    InputChip(
                        selected = false,
                        onClick = { write(values - v) },
                        label = { Text(v, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { text -> if (text.endsWith(',')) { input = text.dropLast(1); commit() } else input = text },
            placeholder = { Text(f.placeholder ?: "+") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            trailingIcon = {
                IconButton(onClick = { commit() }, enabled = input.isNotBlank()) { Icon(Icons.Default.Add, contentDescription = null) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = f.suggestions.filter { it !in values }
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { s -> AssistChip(onClick = { write(values + s) }, label = { Text(s, style = MaterialTheme.typography.bodySmall) }) }
            }
        }
        Hint(f.hint)
    }
}

@Composable
private fun LinesView(f: LinesField, scope: EditorScope) {
    val lines = scope.draft[f.path].strings()
    var text by remember(f.path) { mutableStateOf(lines.joinToString("\n")) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            scope.update { it.set(f.path, jsonStrings(if (input.isEmpty()) emptyList() else input.split('\n'))) }
        },
        label = { Text(f.label.text()) },
        placeholder = f.placeholder?.let { { Text(it) } },
        minLines = 4,
        maxLines = 12,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MapView(f: MapField, scope: EditorScope) {
    val map = scope.draft[f.path].obj() ?: JsonObject(emptyMap())
    val entries = map.entries.toList()
    fun write(pairs: List<Pair<String, String>>) = scope.update { d ->
        d.set(
            f.path,
            JsonObject(
                pairs.associate { (k, v) ->
                    k to if (f.multiValue) jsonStrings(v.split(',').map { it.trim() }.filter { it.isNotEmpty() }) else json(v)
                },
            ),
        )
    }
    val pairs = entries.map { (k, v) -> k to if (f.multiValue) v.strings().joinToString(", ") else v.str().orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label.text(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { write(pairs + ("" to "")) }) { Icon(Icons.Default.Add, contentDescription = null) }
        }
        pairs.forEachIndexed { index, (key, value) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { k -> write(pairs.mapIndexed { i, p -> if (i == index) k to p.second else p }) },
                    placeholder = { Text(f.keyHint) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> write(pairs.mapIndexed { i, p -> if (i == index) p.first to v else p }) },
                    placeholder = { Text(f.valueHint) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { write(pairs.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Default.Delete, contentDescription = null) }
            }
        }
    }
}

@Composable
private fun SectionView(f: Section, scope: EditorScope) {
    val toggle = f.toggle
    val on = toggle == null || (toggle.isOn?.invoke(scope.draft) ?: (scope.draft[toggle.path] != null))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.title.text(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (toggle != null) {
                    Switch(
                        checked = on,
                        onCheckedChange = { enable ->
                            scope.update { d -> toggle.onOff?.invoke(d, enable) ?: d.set(toggle.path, if (enable) toggle.create(d) else null) }
                        },
                    )
                }
            }
            Hint(f.hint)
            if (on) FieldList(f.fields, scope)
        }
    }
}

@Composable
private fun ListView(f: ListField, scope: EditorScope) {
    val items = (scope.draft[f.path] as? JsonArray).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.title.text(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.update { d -> d.set(f.path, JsonArray(items + f.newItem(d))) } }) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
        Hint(f.hint)
        items.forEachIndexed { index, item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.itemTitle(item.obj() ?: JsonObject(emptyMap()), index), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (index > 0) {
                            IconButton(onClick = { scope.update { d -> d.set(f.path, JsonArray(items.toMutableList().apply { add(index - 1, removeAt(index)) })) } }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                            }
                        }
                        if (index < items.size - 1) {
                            IconButton(onClick = { scope.update { d -> d.set(f.path, JsonArray(items.toMutableList().apply { add(index + 1, removeAt(index)) })) } }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                            }
                        }
                        if (items.size > f.minItems) {
                            IconButton(onClick = { scope.update { d -> d.set(f.path, JsonArray(items.filterIndexed { i, _ -> i != index })) } }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    FieldList(f.itemFields(f.path + index), scope)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsView(f: Actions, scope: EditorScope) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        f.actions.forEach { action ->
            val destructive = when (action) {
                is LocalAction -> action.destructive
                is RemoteAction -> action.destructive
            }
            val onClick = {
                when (action) {
                    is LocalAction -> scope.update(action.run)
                    is RemoteAction -> scope.remote(action.id, action.arg)
                }
            }
            if (destructive) {
                TextButton(onClick = onClick, enabled = !scope.busy) { Text(action.label.text(), color = MaterialTheme.colorScheme.error) }
            } else {
                OutlinedButton(onClick = onClick, enabled = !scope.busy) { Text(action.label.text()) }
            }
        }
    }
}

@Composable
private fun InfoView(f: InfoField, scope: EditorScope) {
    val text = f.text(scope.draft) ?: return
    Column {
        f.label?.let { Text(it.text(), style = MaterialTheme.typography.labelLarge) }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (f.monospace) FontFamily.Monospace else null,
            color = if (f.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

@Composable
private fun JsonEditorView(f: JsonEditorField, scope: EditorScope) {
    val current = scope.draft[f.path]
    val rendered = current?.let { prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }.orEmpty()
    var text by remember(f.path) { mutableStateOf(rendered) }
    var error by remember(f.path) { mutableStateOf(false) }
    // Follow outside changes (form edits on other tabs) while the text is not being edited into something else.
    LaunchedEffect(rendered) {
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
        if (parsed != current) text = rendered
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (input.isBlank()) {
                error = false
                scope.update { it.set(f.path, f.emptyValue) }
            } else {
                val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(input) }.getOrNull()
                error = parsed == null
                if (parsed != null) scope.update { it.set(f.path, parsed) }
            }
        },
        label = { Text(f.label.text()) },
        isError = error,
        supportingText = (if (error) Txt("Not valid JSON yet; the last valid value is kept", "Пока не валидный JSON; действует последнее верное значение").text() else f.hint?.text())?.let { { Text(it) } },
        minLines = 6,
        maxLines = 24,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}
