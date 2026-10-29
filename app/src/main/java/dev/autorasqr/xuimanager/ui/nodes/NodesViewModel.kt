package dev.autorasqr.xuimanager.ui.nodes

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.NodesSnapshot
import dev.autorasqr.xuimanager.domain.model.PendingNode
import dev.autorasqr.xuimanager.domain.model.RebootProgress
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.domain.repository.ServerPowerRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.NodesRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NodesMessage(@param:StringRes val text: Int? = null, val error: AppError? = null, val probe: Pair<String, NodeTestResult>? = null)

/** What can be done to a node's own server; only for nodes the app reaches over SSH. */
enum class PowerAction { RESTART_PANEL, REBOOT }

data class NodesUiState(
    val snapshot: NodesSnapshot? = null,
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** Nodes and pending servers with an action in flight. */
    val busy: Set<String> = emptySet(),
    val confirmDelete: Node? = null,
    val confirmForget: PendingNode? = null,
    val addChooser: Boolean = false,
    /** Restart or reboot waiting for confirmation. */
    val confirmPower: Pair<Node, PowerAction>? = null,
    val message: NodesMessage? = null,
)

@HiltViewModel
class NodesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NodeRepository,
    private val servers: ServerRepository,
    private val power: ServerPowerRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<NodesRoute>().serverId

    private val _state = MutableStateFlow(NodesUiState())
    val state: StateFlow<NodesUiState> = _state.asStateFlow()

    private var polling: Job? = null

    /** The panel refreshes node heartbeats itself every few seconds; follow it while the screen is visible. */
    fun startUpdates() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                load()
                delay(REFRESH_MS)
            }
        }
    }

    fun stopUpdates() {
        polling?.cancel()
        polling = null
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun openAdd() = _state.update { it.copy(addChooser = true) }
    fun dismissAdd() = _state.update { it.copy(addChooser = false) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setEnabled(node: Node, enabled: Boolean) = act(node.key) {
        repository.setEnabled(serverId, node.id, enabled)
        load()
    }

    fun probe(node: Node) = act(node.key) {
        val result = repository.probe(serverId, node.id)
        _state.update { it.copy(message = NodesMessage(probe = node.name to result)) }
        load()
    }

    fun requestDelete(node: Node) = _state.update { it.copy(confirmDelete = node) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }

    fun delete() {
        val node = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        act(node.key) {
            repository.delete(serverId, node.id)
            _state.update { it.copy(message = NodesMessage(text = R.string.node_deleted)) }
            load()
        }
    }

    fun register(pending: PendingNode) = act(pending.key) {
        repository.register(serverId, pending.serverId)
        _state.update { it.copy(message = NodesMessage(text = R.string.node_registered)) }
        load()
    }

    fun rebootProgress(nodeServerId: Long): StateFlow<RebootProgress?> = power.rebootProgress(nodeServerId)
    fun dismissReboot(nodeServerId: Long) = power.clearRebootProgress(nodeServerId)

    fun requestPower(node: Node, action: PowerAction) {
        if (node.serverId != null) _state.update { it.copy(confirmPower = node to action) }
    }

    fun dismissPower() = _state.update { it.copy(confirmPower = null) }

    fun confirmPower() {
        val (node, action) = _state.value.confirmPower ?: return
        _state.update { it.copy(confirmPower = null) }
        val nodeServerId = node.serverId ?: return
        when (action) {
            PowerAction.REBOOT -> power.reboot(nodeServerId)
            PowerAction.RESTART_PANEL -> act(node.key) {
                power.restartPanel(nodeServerId)
                _state.update { it.copy(message = NodesMessage(text = R.string.panel_restarted)) }
                load()
            }
        }
    }

    fun requestForget(pending: PendingNode) = _state.update { it.copy(confirmForget = pending) }
    fun dismissForget() = _state.update { it.copy(confirmForget = null) }

    /** Drops a server the app was setting up as a node; nothing on the server itself is touched. */
    fun forget() {
        val pending = _state.value.confirmForget ?: return
        _state.update { it.copy(confirmForget = null) }
        act(pending.key) {
            servers.delete(pending.serverId)
            load()
        }
    }

    private fun act(key: String, block: suspend () -> Unit) {
        if (key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = NodesMessage(error = e.asAppError())) }
            } finally {
                _state.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(refreshing = true) }
        try {
            val snapshot = repository.load(serverId)
            _state.update { it.copy(snapshot = snapshot, error = null, refreshing = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.asAppError(), refreshing = false) }
        }
    }

    private companion object {
        const val REFRESH_MS = 10_000L
    }
}

internal val Node.key: String get() = "node:$id:$name"
internal val PendingNode.key: String get() = "server:$serverId"
