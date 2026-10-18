package dev.autorasqr.xuimanager.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.Fingerprints
import dev.autorasqr.xuimanager.data.deploy.SniPresets
import dev.autorasqr.xuimanager.domain.model.ValidationField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectField(
    label: String,
    selected: T,
    options: List<T>,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    error: ValidationField? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(stringResource(it.messageRes())) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Free text with the preset list as suggestions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SniField(value: String, onChange: (String) -> Unit, error: ValidationField?, hint: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.deploy_sni_label)) },
            isError = error != null,
            supportingText = {
                Text(if (error != null) stringResource(error.messageRes()) else hint ?: stringResource(R.string.field_sni_hint))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SniPresets.targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target) },
                    onClick = {
                        onChange(target)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun FingerprintField(value: String, onSelect: (String) -> Unit) {
    SelectField(
        label = stringResource(R.string.field_fingerprint),
        selected = value,
        options = Fingerprints.all,
        text = { it },
        onSelect = onSelect,
    )
    Text(
        stringResource(R.string.field_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
