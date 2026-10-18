package dev.autorasqr.xuimanager.ui.routing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.ClientRouting
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.RoutingJobProgress
import dev.autorasqr.xuimanager.domain.model.RoutingState
import dev.autorasqr.xuimanager.domain.repository.RoutingRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.RoutingRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutingUiState(
    val state: RoutingState? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    /** Profile being applied (the panel restarts), null when idle. */
    val applying: ClientRouting? = null,
    val applied: Boolean = false,
    val confirmServerBlock: Boolean = false,
    val job: RoutingJobProgress? = null,
)

@HiltViewModel
class RoutingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RoutingRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<RoutingRoute>().serverId
    private val _state = MutableStateFlow(RoutingUiState())
    val state: StateFlow<RoutingUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            var previous: DeployState? = null
            repository.serverBlockProgress(serverId).collect { job ->
                _state.update { it.copy(job = job) }
                if (previous == DeployState.RUNNING && job?.state != DeployState.RUNNING) refresh()
                previous = job?.state
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val routing = repository.load(serverId)
                _state.update { it.copy(state = routing, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asAppError()) }
            }
        }
    }

    fun selectProfile(profile: ClientRouting) {
        val current = _state.value
        if (current.applying != null || current.state?.clientRouting == profile || profile == ClientRouting.CUSTOM) return
        _state.update { it.copy(applying = profile, applied = false, error = null) }
        viewModelScope.launch {
            try {
                repository.setClientRouting(serverId, profile)
                _state.update { it.copy(applying = null, applied = true, state = it.state?.copy(clientRouting = profile)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(applying = null, error = e.asAppError()) }
            }
        }
    }

    fun toggleServerBlock(enabled: Boolean) {
        if (_state.value.job?.state == DeployState.RUNNING) return
        if (enabled) _state.update { it.copy(confirmServerBlock = true) } else repository.setServerBlock(serverId, false)
    }

    fun confirmServerBlock() {
        _state.update { it.copy(confirmServerBlock = false) }
        repository.setServerBlock(serverId, true)
    }

    fun dismissServerBlock() = _state.update { it.copy(confirmServerBlock = false) }
    fun dismissJob() = repository.clearServerBlockProgress(serverId)
}
