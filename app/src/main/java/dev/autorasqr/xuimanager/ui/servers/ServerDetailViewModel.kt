package dev.autorasqr.xuimanager.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.PanelLogin
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.ServerStatus
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.ServerDetailRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

@HiltViewModel
class ServerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val deployRepository: DeployRepository,
    private val statusRepository: PanelStatusRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<ServerDetailRoute>().serverId

    val server: StateFlow<Server?> = serverRepository.observeServer(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deployRunning = MutableStateFlow(false)
    val deployRunning: StateFlow<Boolean> = _deployRunning.asStateFlow()

    private val _status = MutableStateFlow(StatusUiState())
    val status: StateFlow<StatusUiState> = _status.asStateFlow()

    private var polling: Job? = null

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
