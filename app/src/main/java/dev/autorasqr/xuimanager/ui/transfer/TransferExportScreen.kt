package dev.autorasqr.xuimanager.ui.transfer

import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.repository.TransferExport
import dev.autorasqr.xuimanager.domain.repository.TransferRepository
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.Clipboard
import dev.autorasqr.xuimanager.ui.common.QrCode
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.common.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ExportState {
    /** The warning is shown; nothing is generated until the user asks for it. */
    data object Warning : ExportState
    data object Preparing : ExportState
    data class Ready(val export: TransferExport) : ExportState
    data object Failed : ExportState
}

@HiltViewModel
class TransferExportViewModel @Inject constructor(private val repository: TransferRepository) : ViewModel() {
    private val _state = MutableStateFlow<ExportState>(ExportState.Warning)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun reveal() {
        if (_state.value is ExportState.Preparing || _state.value is ExportState.Ready) return
        _state.value = ExportState.Preparing
        viewModelScope.launch {
            _state.value = runCatching { repository.export() }.fold({ ExportState.Ready(it) }, { ExportState.Failed })
        }
    }
}

/** Old phone: shows the app's servers as QR codes and as a link with a separate code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferExportScreen(onBack: () -> Unit, viewModel: TransferExportViewModel = hiltViewModel()) {
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(stringResource(R.string.transfer_export_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                ExportState.Warning -> WarningContent(onReveal = viewModel::reveal)
                ExportState.Preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ExportState.Failed -> Text(
                    stringResource(R.string.transfer_export_failed),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                is ExportState.Ready -> ReadyContent(current.export)
            }
        }
    }
}

@Composable
private fun WarningContent(onReveal: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.transfer_export_intro), style = MaterialTheme.typography.bodyMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                stringResource(R.string.transfer_export_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
        Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.transfer_export_show)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(export: TransferExport) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.transfer_tab_qr)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.transfer_tab_link)) })
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.transfer_export_servers, export.serverCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tab == 0) QrContent(export.qrCodes) else LinkContent(export)
        }
    }
}

@Composable
private fun QrContent(codes: List<String>) {
    FullBrightness()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var playing by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(playing, codes.size) {
        while (playing && codes.size > 1) {
            delay(QR_FRAME_MS)
            index = (index + 1) % codes.size
        }
    }
    Text(stringResource(R.string.transfer_qr_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    QrCode(codes[index.coerceIn(codes.indices)], size = 320.dp)
    if (codes.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { playing = false; index = (index - 1 + codes.size) % codes.size }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.transfer_qr_prev))
            }
            Text(
                stringResource(R.string.transfer_qr_counter, index + 1, codes.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(onClick = { playing = false; index = (index + 1) % codes.size }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.transfer_qr_next))
            }
        }
        OutlinedButton(onClick = { playing = !playing }) {
            Text(stringResource(if (playing) R.string.transfer_qr_pause else R.string.transfer_qr_play))
        }
    }
}

@Composable
private fun LinkContent(export: TransferExport) {
    val context = LocalContext.current
    val linkLabel = stringResource(R.string.transfer_tab_link)
    val shareTitle = stringResource(R.string.share)
    Text(stringResource(R.string.transfer_link_hint), style = MaterialTheme.typography.bodyMedium)
    Card(Modifier.fillMaxWidth()) {
        Text(
            export.link,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(16.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { Clipboard.copy(context, linkLabel, export.link, sensitive = true) }) {
            Text(stringResource(R.string.copy))
        }
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, export.link)
            context.startActivity(Intent.createChooser(send, shareTitle))
        }) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(shareTitle)
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.transfer_code_label), style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    export.code,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Text(
                stringResource(R.string.transfer_code_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Screen at full brightness while the QR codes are shown, so the other phone's camera reads them easily. */
@Composable
private fun FullBrightness() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.attributes = window.attributes.apply { screenBrightness = 1f }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.attributes = window.attributes.apply {
                screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private const val QR_FRAME_MS = 900L
