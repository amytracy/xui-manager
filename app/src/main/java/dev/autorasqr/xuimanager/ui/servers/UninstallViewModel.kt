package dev.autorasqr.xuimanager.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.UninstallProgress
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.repository.UninstallRepository
import dev.autorasqr.xuimanager.ui.navigation.UninstallRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the user types to confirm; any letter case, since keyboards like to capitalise the first one. */
const val UNINSTALL_CONFIRM_WORD = "yes"

fun isUninstallConfirmed(input: String) = input.trim().equals(UNINSTALL_CONFIRM_WORD, ignoreCase = true)

@HiltViewModel
class UninstallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    serverRepository: ServerRepository,
    private val deployRepository: DeployRepository,
    private val uninstall: UninstallRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<UninstallRoute>().serverId

    /** The server and the node servers that are cleaned before it; empty once they are gone from the app. */
    val targets: StateFlow<List<Server>> = serverRepository.observeServers()
        .map { all -> all.filter { it.masterServerId == serverId } + all.filter { it.id == serverId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<UninstallProgress?> = uninstall.progress(serverId)

    private val _deployRunning = MutableStateFlow(false)
    val deployRunning: StateFlow<Boolean> = _deployRunning.asStateFlow()

    /** Remembered at start: a node goes back to its master's node list, anything else to the start screen. */
    var wasNode: Boolean = false
        private set

    init {
        viewModelScope.launch { _deployRunning.value = deployRepository.unfinishedJob(serverId) != null }
    }

    fun start(confirmation: String) {
        if (!isUninstallConfirmed(confirmation) || _deployRunning.value) return
        if (progress.value?.state == DeployState.RUNNING) return
        wasNode = targets.value.lastOrNull()?.isNode == true
        uninstall.start(serverId)
    }

    /** Leaves a finished run behind; a failed one is shown again until it is retried or dismissed here. */
    fun dismiss() = uninstall.clearProgress(serverId)
}
