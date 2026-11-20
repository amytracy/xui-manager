package dev.autorasqr.xuimanager.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.transfer.TransferBundle
import dev.autorasqr.xuimanager.data.transfer.TransferCodec
import dev.autorasqr.xuimanager.data.transfer.TransferException
import dev.autorasqr.xuimanager.domain.repository.TransferRepository
import dev.autorasqr.xuimanager.ui.common.AppTopBar
import dev.autorasqr.xuimanager.ui.common.SecureScreen
import dev.autorasqr.xuimanager.ui.common.appBarScrollBehavior
import dev.autorasqr.xuimanager.ui.navigation.TransferImportRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportState(
    val tab: Int = TAB_SCAN,
    val received: Int = 0,
    val expected: Int = 0,
    val link: String = "",
    val code: String = "",
    val busy: Boolean = false,
    /** Problem with the last attempt; null when there is none. */
    val error: ImportError? = null,
    /** Id of the home server once everything is saved. */
    val doneServerId: Long? = null,
) {
    companion object {
        const val TAB_SCAN = 0
        const val TAB_LINK = 1
    }
}

enum class ImportError { NOT_A_TRANSFER, WRONG_CODE, DAMAGED, NEWER_VERSION, OTHER_TRANSFER, SAVE_FAILED }

@HiltViewModel
class TransferImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TransferRepository,
) : ViewModel() {
    private val initialLink = savedStateHandle.toRoute<TransferImportRoute>().link
    private val _state = MutableStateFlow(
        ImportState(link = initialLink, tab = if (initialLink.isBlank()) ImportState.TAB_SCAN else ImportState.TAB_LINK),
    )
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var collector = TransferCodec.QrCollector()

    fun onTab(tab: Int) = _state.update { it.copy(tab = tab, error = null) }
    fun onLink(value: String) = _state.update { it.copy(link = value, error = null) }
    fun onCode(value: String) = _state.update { it.copy(code = value, error = null) }

    fun onQrScanned(text: String) {
        if (_state.value.busy || _state.value.doneServerId != null) return
        // A transfer link shown as a QR code by some other app: move it to the link tab.
        if (TransferCodec.isLink(text)) {
            _state.update { it.copy(tab = ImportState.TAB_LINK, link = text, error = null) }
            return
        }
        try {
            collector.add(text)
        } catch (e: TransferException) {
            _state.update { it.copy(error = e.reason.toImportError()) }
            return
        }
        _state.update { it.copy(received = collector.received, expected = collector.expected, error = null) }
        if (collector.isComplete) {
            val complete = collector
            save { complete.open() }
        }
    }

    /** Starts the QR scan over, e.g. after codes of another transfer got mixed in. */
    fun resetScan() {
        collector = TransferCodec.QrCollector()
        _state.update { it.copy(received = 0, expected = 0, error = null) }
    }

    fun submitLink() {
        val current = _state.value
        if (current.busy) return
        if (!TransferCodec.isLink(current.link)) {
            _state.update { it.copy(error = ImportError.NOT_A_TRANSFER) }
            return
        }
        save { repository.openLink(current.link, current.code) }
    }

    private fun save(open: suspend () -> TransferBundle) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val bundle = try {
                open()
            } catch (e: TransferException) {
                if (e.reason != TransferException.Reason.WRONG_CODE) resetScan()
                _state.update { it.copy(busy = false, error = e.reason.toImportError()) }
                return@launch
            }
            runCatching { repository.import(bundle) }
                .onSuccess { id -> _state.update { it.copy(busy = false, doneServerId = id) } }
                .onFailure { _state.update { it.copy(busy = false, error = ImportError.SAVE_FAILED) } }
        }
    }

    private fun TransferException.Reason.toImportError() = when (this) {
        TransferException.Reason.NOT_A_TRANSFER -> ImportError.NOT_A_TRANSFER
        TransferException.Reason.WRONG_CODE -> ImportError.WRONG_CODE
        TransferException.Reason.DAMAGED -> ImportError.DAMAGED
        TransferException.Reason.NEWER_VERSION -> ImportError.NEWER_VERSION
        TransferException.Reason.OTHER_TRANSFER -> ImportError.OTHER_TRANSFER
    }
}

/** New phone: takes the servers of another phone from its QR codes or from its link and code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferImportScreen(
    onBack: (() -> Unit)?,
    onDone: (Long) -> Unit,
    viewModel: TransferImportViewModel = hiltViewModel(),
) {
    SecureScreen()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.doneServerId) { state.doneServerId?.let(onDone) }
    val appBarScroll = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                scrollBehavior = appBarScroll,
                title = { Text(stringResource(R.string.transfer_import_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            PrimaryTabRow(selectedTabIndex = state.tab) {
                Tab(
                    selected = state.tab == ImportState.TAB_SCAN,
                    onClick = { viewModel.onTab(ImportState.TAB_SCAN) },
                    text = { Text(stringResource(R.string.transfer_tab_scan)) },
                )
                Tab(
                    selected = state.tab == ImportState.TAB_LINK,
                    onClick = { viewModel.onTab(ImportState.TAB_LINK) },
                    text = { Text(stringResource(R.string.transfer_tab_link)) },
                )
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.tab == ImportState.TAB_SCAN) {
                    ScanContent(state, viewModel::onQrScanned, viewModel::resetScan)
                } else {
                    LinkContent(state, viewModel::onLink, viewModel::onCode, viewModel::submitLink)
                }
                state.error?.let { error ->
                    Text(stringResource(error.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.busy) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

@Composable
private fun ScanContent(state: ImportState, onCode: (String) -> Unit, onReset: () -> Unit) {
    Text(stringResource(R.string.transfer_scan_hint), style = MaterialTheme.typography.bodyMedium)
    QrScanner(
        onCode = onCode,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
    )
    if (state.expected > 0) {
        LinearProgressIndicator(
            progress = { state.received.toFloat() / state.expected },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.transfer_scan_progress, state.received, state.expected),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (state.error == ImportError.OTHER_TRANSFER) {
        OutlinedButton(onClick = onReset) { Text(stringResource(R.string.transfer_scan_restart)) }
    }
}

@Composable
private fun LinkContent(state: ImportState, onLink: (String) -> Unit, onCode: (String) -> Unit, onSubmit: () -> Unit) {
    Text(stringResource(R.string.transfer_link_import_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.link,
        onValueChange = onLink,
        label = { Text(stringResource(R.string.transfer_link_field)) },
        minLines = 3,
        maxLines = 6,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.code,
        onValueChange = onCode,
        label = { Text(stringResource(R.string.transfer_code_field)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onSubmit,
        enabled = !state.busy && state.link.isNotBlank() && state.code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.transfer_import_action)) }
}

private fun ImportError.messageRes(): Int = when (this) {
    ImportError.NOT_A_TRANSFER -> R.string.transfer_error_not_transfer
    ImportError.WRONG_CODE -> R.string.transfer_error_wrong_code
    ImportError.DAMAGED -> R.string.transfer_error_damaged
    ImportError.NEWER_VERSION -> R.string.transfer_error_newer
    ImportError.OTHER_TRANSFER -> R.string.transfer_error_other
    ImportError.SAVE_FAILED -> R.string.transfer_error_save
}
