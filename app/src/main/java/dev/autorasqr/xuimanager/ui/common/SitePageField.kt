package dev.autorasqr.xuimanager.ui.common

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.SitePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** The decoy page picked for self-steal: [bytes] null means a random template. */
class SitePageChoice(val name: String? = null, val bytes: ByteArray? = null, val invalid: Boolean = false)

/** Reads and checks an index.html the user picked; a bad file comes back as [SitePageChoice.invalid]. */
suspend fun readSitePage(resolver: ContentResolver, uri: Uri): SitePageChoice = withContext(Dispatchers.IO) {
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    // One byte over the limit is enough to tell that the file is too big.
    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readNBytesCompat(SitePage.MAX_BYTES + 1) } }.getOrNull()
    if (bytes != null && SitePage.validate(bytes)) SitePageChoice(name ?: "index.html", bytes) else SitePageChoice(invalid = true)
}

@Composable
fun SitePageField(choice: SitePageChoice, onPick: (ContentResolver, Uri) -> Unit, onReset: () -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPick(context.contentResolver, uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.site_page_title), style = MaterialTheme.typography.titleSmall)
        Text(
            choice.name ?: stringResource(R.string.site_page_random),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Many file managers report .html as octet-stream, so any file can be picked and is checked afterwards.
            OutlinedButton(onClick = { picker.launch(arrayOf("text/html", "*/*")) }, enabled = enabled) {
                Text(stringResource(R.string.site_page_pick))
            }
            if (choice.bytes != null) {
                TextButton(onClick = onReset, enabled = enabled) { Text(stringResource(R.string.site_page_reset)) }
            }
        }
        if (choice.invalid) {
            Text(stringResource(R.string.site_page_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.site_page_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (buffer.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
