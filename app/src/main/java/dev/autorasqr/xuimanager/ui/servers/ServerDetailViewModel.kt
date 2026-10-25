package dev.autorasqr.xuimanager.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppUpdateOffer
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.PanelLogin
import dev.autorasqr.xuimanager.domain.model.PanelUpdateOffer
import dev.autorasqr.xuimanager.domain.model.PanelUpdateProgress
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.ServerStatus
import dev.autorasqr.xuimanager.domain.model.SystemUpdateProgress
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import dev.autorasqr.xuimanager.domain.model.XrayUpdateProgress
import dev.autorasqr.xuimanager.domain.repository.AppUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import dev.autorasqr.xuimanager.domain.repository.PanelUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.repository.SystemUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.XrayUpdateRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.ServerDetailRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatusUiState(
    val status: ServerStatus? = null,
    val error: AppError? = null,
    val loading: Boolean = false,
    val confirmRestart: Boolean = false,
    val restarting: Boolean = false,
    /** True right after a successful restart, until the next refresh. */
    val restarted: Boolean = false,
)

/** What the update checks found; null while a check has not finished yet. */
data class UpdatesUiState(
    val app: UpdateCheck<AppUpdateOffer>? = null,
    val panel: UpdateCheck<PanelUpdateOffer>? = null,
    val xray: UpdateCheck<XrayUpdateOffer>? = null,
    val checking: Boolean = false,
) {
    val appOffer get() = (app as? UpdateCheck.Available)?.offer
    val panelOffer get() = (panel as? UpdateCheck.Available)?.offer
    val xrayOffer get() = (xray as? UpdateCheck.Available)?.offer
}

/** A 3x-ui the server already runs although the app has no panel for it (a new phone): restore it instead of deploying. */
data class InstalledPanel(val version: String)

/** The offer dialogs shown once per app run, one after another. */
enum class UpdateDialog { APP, PANEL, XRAY }

@HiltViewModel
class ServerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val deployRepository: DeployRepository,
    private val statusRepository: PanelStatusRepository,
    private val panelUpdates: PanelUpdateRepository,
    private val systemUpdates: SystemUpdateRepository,
    private val xrayUpdates: XrayUpdateRepository,
    private val appUpdates: AppUpdateRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<ServerDetailRoute>().serverId

    val server: StateFlow<Server?> = serverRepository.observeServer(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deployRunning = MutableStateFlow(false)
    val deployRunning: StateFlow<Boolean> = _deployRunning.asStateFlow()

    private val _status = MutableStateFlow(StatusUiState())
    val status: StateFlow<StatusUiState> = _status.asStateFlow()

    private var polling: Job? = null

    /** Newer app, 3x-ui (main panel or its nodes) and Xray releases; checked once per app run or on request. */
    private val _updates = MutableStateFlow(UpdatesUiState())
    val updates: StateFlow<UpdatesUiState> = _updates.asStateFlow()

    private val pendingDialogs = ArrayDeque<UpdateDialog>()
    private val _updateDialog = MutableStateFlow<UpdateDialog?>(null)
    val updateDialog: StateFlow<UpdateDialog?> = _updateDialog.asStateFlow()

    val panelUpdate: StateFlow<PanelUpdateProgress?> = panelUpdates.progress(serverId)
    val xrayUpdate: StateFlow<XrayUpdateProgress?> = xrayUpdates.progress(serverId)
    val systemUpdate: StateFlow<SystemUpdateProgress?> = systemUpdates.progress(serverId)

    private val _confirmSystemUpdate = MutableStateFlow(false)
    val confirmSystemUpdate: StateFlow<Boolean> = _confirmSystemUpdate.asStateFlow()

    private val _installedPanel = MutableStateFlow<InstalledPanel?>(null)
    val installedPanel: StateFlow<InstalledPanel?> = _installedPanel.asStateFlow()

    init {
        viewModelScope.launch {
            // Read-only look over SSH; when it fails (offline, host key not confirmed yet) the card offers a deploy as before.
            val current = server.first { it != null } ?: return@launch
            if (current.panel != null) return@launch
            val precheck = try {
                deployRepository.precheck(serverId, checkDomain = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (precheck.existingInstalled) _installedPanel.value = InstalledPanel(precheck.existingVersion.orEmpty())
        }
        viewModelScope.launch {
            server.first { it != null }
            val found = checkAll(refresh = false)
            if (found.appOffer != null && appUpdates.shouldPrompt()) {
                appUpdates.markPrompted()
                pendingDialogs += UpdateDialog.APP
            }
            if (found.panelOffer != null && panelUpdates.shouldPrompt(serverId) && panelUpdate.value?.state != DeployState.RUNNING) {
                panelUpdates.markPrompted(serverId)
                pendingDialogs += UpdateDialog.PANEL
            }
            if (found.xrayOffer != null && xrayUpdates.shouldPrompt(serverId) && xrayUpdate.value?.state != DeployState.RUNNING) {
                xrayUpdates.markPrompted(serverId)
                pendingDialogs += UpdateDialog.XRAY
            }
            showNextDialog()
        }
        viewModelScope.launch {
            // Once an update ends, what is left to offer (a node that did not make it) comes from a fresh check.
            panelUpdate.map { it?.state }.distinctUntilChanged().collect { state ->
                if (state == DeployState.SUCCEEDED || state == DeployState.FAILED) {
                    val panel = panelUpdates.check(serverId, refresh = true)
                    _updates.update { it.copy(panel = panel) }
                }
            }
        }
        viewModelScope.launch {
            xrayUpdate.map { it?.state }.distinctUntilChanged().collect { state ->
                if (state == DeployState.SUCCEEDED || state == DeployState.FAILED) {
                    val xray = xrayUpdates.check(serverId, refresh = true)
                    _updates.update { it.copy(xray = xray) }
                    loadStatus()
                }
            }
        }
    }

    /** "Check for updates": asks GitHub and the panel again, without the offer dialogs. */
    fun checkUpdates() {
        if (_updates.value.checking) return
        viewModelScope.launch { checkAll(refresh = true) }
    }

    private suspend fun checkAll(refresh: Boolean): UpdatesUiState {
        _updates.update { it.copy(checking = true) }
        val current = server.value
        val result = coroutineScope {
            val app = async { appUpdates.check(refresh) }
            // Nodes are updated from their main panel's offer; their own Xray is updated here.
            val panel = async {
                if (current == null || current.isNode || current.panel == null) UpdateCheck.Unavailable() else panelUpdates.check(serverId, refresh)
            }
            val xray = async { if (current?.panel == null) UpdateCheck.Unavailable() else xrayUpdates.check(serverId, refresh) }
            UpdatesUiState(app.await(), panel.await(), xray.await())
        }
        _updates.value = result
        return result
    }

    private fun showNextDialog() {
        _updateDialog.value = pendingDialogs.removeFirstOrNull()
    }

    fun dismissUpdateDialog() = showNextDialog()

    /** Panel address, login and password; reloaded whenever the stored panel details change (e.g. after a deploy). */
    val panelLogin: StateFlow<PanelLogin?> = server
        .map { it?.panel }
        .distinctUntilChanged()
        .map { panel -> if (panel == null) null else serverRepository.panelCredentials(serverId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Called whenever the screen becomes visible, since a deployment may have finished meanwhile. */
    fun refreshDeployState() {
        viewModelScope.launch { _deployRunning.value = deployRepository.unfinishedJob(serverId) != null }
    }

    /** Polls the panel while the screen is resumed; the panel itself refreshes its snapshot every 2 s. */
    fun startStatusUpdates() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            server.first { it != null }
            while (isActive) {
                loadStatus()
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    fun stopStatusUpdates() {
        polling?.cancel()
        polling = null
    }

    fun startPanelUpdate() {
        if (_updateDialog.value == UpdateDialog.PANEL) showNextDialog()
        val offer = _updates.value.panelOffer ?: return
        panelUpdates.start(serverId, offer)
    }

    fun dismissPanelUpdate() = panelUpdates.clearProgress(serverId)

    fun startXrayUpdate() {
        if (_updateDialog.value == UpdateDialog.XRAY) showNextDialog()
        val offer = _updates.value.xrayOffer ?: return
        xrayUpdates.start(serverId, offer)
    }

    fun dismissXrayUpdate() = xrayUpdates.clearProgress(serverId)

    fun requestSystemUpdate() {
        _confirmSystemUpdate.value = true
    }

    fun dismissSystemUpdateConfirm() {
        _confirmSystemUpdate.value = false
    }

    fun startSystemUpdate() {
        _confirmSystemUpdate.value = false
        systemUpdates.start(serverId)
    }

    fun dismissSystemUpdate() = systemUpdates.clearProgress(serverId)

    fun requestRestart() = _status.update { it.copy(confirmRestart = true) }
    fun dismissRestart() = _status.update { it.copy(confirmRestart = false) }

    fun restartXray() {
        _status.update { it.copy(confirmRestart = false, restarting = true, restarted = false) }
        viewModelScope.launch {
            try {
                statusRepository.restartXray(serverId)
                _status.update { it.copy(restarting = false, restarted = true, error = null) }
                delay(1_500)
                loadStatus(keepRestarted = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(restarting = false, error = e.asAppError()) }
            }
        }
    }

    private suspend fun loadStatus(keepRestarted: Boolean = false) {
        if (server.value?.panel == null) return
        _status.update { it.copy(loading = true) }
        try {
            val status = statusRepository.status(serverId)
            _status.update { it.copy(status = status, error = null, loading = false, restarted = keepRestarted && it.restarted) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.update { it.copy(error = e.asAppError(), loading = false) }
        }
    }

    private companion object {
        const val STATUS_INTERVAL_MS = 5_000L
    }
}
