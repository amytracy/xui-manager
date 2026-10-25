package dev.autorasqr.xuimanager.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.RestoreProgress
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.RestoreRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.ui.navigation.RestoreRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    serverRepository: ServerRepository,
    private val deployRepository: DeployRepository,
    private val restore: RestoreRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<RestoreRoute>().serverId

    val server: StateFlow<Server?> = serverRepository.observeServer(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<RestoreProgress?> = restore.progress(serverId)

    private val _deployRunning = MutableStateFlow(false)
    val deployRunning: StateFlow<Boolean> = _deployRunning.asStateFlow()

    init {
        viewModelScope.launch { _deployRunning.value = deployRepository.unfinishedJob(serverId) != null }
    }

    fun start() {
        if (_deployRunning.value || progress.value?.state == DeployState.RUNNING) return
        restore.start(serverId)
    }

    /** Leaves a finished run behind; a failed one is shown again until it is retried or dismissed here. */
    fun dismiss() = restore.clearProgress(serverId)
}
