package dev.autorasqr.xuimanager.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.HostKeyPin

/** Asks the user to trust a host key on first contact, or to accept a changed one. */
@Composable
fun HostKeyDialog(
    host: String,
    presented: HostKeyPin,
    previous: HostKeyPin?,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (previous == null) R.string.host_key_title else R.string.host_key_changed_title)) },
        text = {
            Column {
                Text(stringResource(if (previous == null) R.string.host_key_text else R.string.host_key_changed_text, host))
                Spacer(Modifier.height(12.dp))
                if (previous != null) {
                    Text(stringResource(R.string.host_key_saved), style = MaterialTheme.typography.labelMedium)
                    Fingerprint(previous)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.host_key_presented), style = MaterialTheme.typography.labelMedium)
                }
                Fingerprint(presented)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(stringResource(if (previous == null) R.string.host_key_trust else R.string.host_key_trust_new))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Fingerprint(pin: HostKeyPin) {
    SelectionContainer {
        Text(
            text = "${pin.algorithm}\n${pin.fingerprint}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
