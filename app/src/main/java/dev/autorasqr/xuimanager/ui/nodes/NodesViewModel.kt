package dev.autorasqr.xuimanager.ui.nodes

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeAction
import dev.autorasqr.xuimanager.domain.model.NodeBulkProgress
import dev.autorasqr.xuimanager.domain.model.NodePanelOffer
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.NodesSnapshot
import dev.autorasqr.xuimanager.domain.model.PanelUpdateProgress
import dev.autorasqr.xuimanager.domain.model.PendingNode
import dev.autorasqr.xuimanager.domain.model.RebootProgress
import dev.autorasqr.xuimanager.domain.model.SystemUpdateProgress
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import dev.autorasqr.xuimanager.domain.model.XrayUpdateProgress
import dev.autorasqr.xuimanager.domain.repository.NodeBulkRepository
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import dev.autorasqr.xuimanager.domain.repository.PanelUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.ServerPowerRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.repository.SystemUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.XrayUpdateRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.NodesRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NodesMessage(@param:StringRes val text: Int? = null, val error: AppError? = null, val probe: Pair<String, NodeTestResult>? = null)

/** An action on one node waiting for confirmation, with the update it would install. */
data class NodeActionRequest(
    val node: Node,
    val action: NodeAction,
    val panelOffer: NodePanelOffer? = null,
    val xrayOffer: XrayUpdateOffer? = null,
)

data class NodesUiState(
    val snapshot: NodesSnapshot? = null,
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** Nodes and pending servers with an action in flight. */
    val busy: Set<String> = emptySet(),
    val confirmDelete: Node? = null,
    val confirmForget: PendingNode? = null,
    val addChooser: Boolean = false,
    val confirmAction: NodeActionRequest? = null,
    /** "All nodes": choosing the action, then confirming it. */
    val bulkChooser: Boolean = false,
    val confirmBulk: NodeAction? = null,
    /** Newer 3x-ui per node id, from the main panel's update check. */
    val panelOffers: Map<Int, NodePanelOffer> = emptyMap(),
    /** Newer Xray per node server id, from each node's own panel. */
    val xrayOffers: Map<Long, XrayUpdateOffer> = emptyMap(),
    val message: NodesMessage? = null,
)

@HiltViewModel
class NodesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NodeRepository,
    private val servers: ServerRepository,
    private val power: ServerPowerRepository,
    private val status: PanelStatusRepository,
    private val panelUpdates: PanelUpdateRepository,
    private val xrayUpdates: XrayUpdateRepository,
    private val systemUpdates: SystemUpdateRepository,
    private val bulk: NodeBulkRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<NodesRoute>().serverId

    private val _state = MutableStateFlow(NodesUiState())
    val state: StateFlow<NodesUiState> = _state.asStateFlow()

    val bulkProgress: StateFlow<NodeBulkProgress?> = bulk.progress(serverId)

    private var polling: Job? = null
    private var offersChecked = false

    /** The panel refreshes node heartbeats itself every few seconds; follow it while the screen is visible. */
    fun startUpdates() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                load()
                if (!offersChecked && _state.value.snapshot != null) {
                    offersChecked = true
                    launch { checkOffers(refresh = false) }
                }
                delay(REFRESH_MS)
            }
        }
    }

    fun stopUpdates() {
        polling?.cancel()
        polling = null
    }

    fun refresh() {
        viewModelScope.launch {
            load()
            checkOffers(refresh = true)
        }
    }

    /** After an update or reboot ends: what is still outdated. Cached checks are dropped by the update itself. */
    fun onJobFinished() {
        viewModelScope.launch {
            load()
            checkOffers(refresh = false)
        }
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

    // --- jobs on a node's own server, shared with that server's screen ---

    fun rebootProgress(nodeServerId: Long): StateFlow<RebootProgress?> = power.rebootProgress(nodeServerId)
    fun dismissReboot(nodeServerId: Long) = power.clearRebootProgress(nodeServerId)
    fun systemProgress(nodeServerId: Long): StateFlow<SystemUpdateProgress?> = systemUpdates.progress(nodeServerId)
    fun dismissSystem(nodeServerId: Long) = systemUpdates.clearProgress(nodeServerId)
    fun xrayProgress(nodeServerId: Long): StateFlow<XrayUpdateProgress?> = xrayUpdates.progress(nodeServerId)
    fun dismissXray(nodeServerId: Long) = xrayUpdates.clearProgress(nodeServerId)
    fun panelProgress(nodeId: Int): StateFlow<PanelUpdateProgress?> = panelUpdates.nodeProgress(serverId, nodeId)
    fun dismissPanel(nodeId: Int) = panelUpdates.clearNodeProgress(serverId, nodeId)

    /**
     * Asks to confirm [action] on [node]. An update with nothing known to install is checked first and either
     * confirmed with what it found or reported as up to date. Actions needing SSH are only offered for nodes the app
     * has as servers; the screen sends the rest to "Connect over SSH".
     */
    fun requestAction(node: Node, action: NodeAction) {
        val nodeServerId = node.serverId
        if (action.needsSsh && nodeServerId == null) return
        when (action) {
            NodeAction.UPDATE_PANEL -> {
                val known = _state.value.panelOffers[node.id]
                if (known != null) {
                    _state.update { it.copy(confirmAction = NodeActionRequest(node, action, panelOffer = known)) }
                } else {
                    act(node.key) {
                        val offer = when (val check = panelUpdates.check(serverId, refresh = true)) {
                            is UpdateCheck.Available -> check.offer.nodes.firstOrNull { it.nodeId == node.id }
                                ?.let { NodePanelOffer(node.id, it.name, it.currentVersion, check.offer.latestVersion) }
                            UpdateCheck.UpToDate -> null
                            // The check itself failed (no GitHub access from the server, panel down): say so.
                            is UpdateCheck.Unavailable -> throw AppException(check.error ?: AppError.Unexpected("3x-ui update not available"))
                        }
                        _state.update { s ->
                            if (offer == null) {
                                s.copy(panelOffers = s.panelOffers - node.id, message = NodesMessage(text = R.string.node_panel_up_to_date))
                            } else {
                                s.copy(panelOffers = s.panelOffers + (node.id to offer), confirmAction = NodeActionRequest(node, action, panelOffer = offer))
                            }
                        }
                    }
                }
            }
            NodeAction.UPDATE_XRAY -> {
                val id = nodeServerId ?: return
                val known = _state.value.xrayOffers[id]
                if (known != null) {
                    _state.update { it.copy(confirmAction = NodeActionRequest(node, action, xrayOffer = known)) }
                } else {
                    act(node.key) {
                        when (val check = xrayUpdates.check(id, refresh = true)) {
                            is UpdateCheck.Available -> _state.update { s ->
                                s.copy(
                                    xrayOffers = s.xrayOffers + (id to check.offer),
                                    confirmAction = NodeActionRequest(node, action, xrayOffer = check.offer),
                                )
                            }
                            UpdateCheck.UpToDate -> _state.update { s ->
                                s.copy(xrayOffers = s.xrayOffers - id, message = NodesMessage(text = R.string.node_xray_up_to_date))
                            }
                            is UpdateCheck.Unavailable -> _state.update {
                                it.copy(message = NodesMessage(error = check.error ?: AppError.Unexpected("Xray update not available")))
                            }
                        }
                    }
                }
            }
            else -> _state.update { it.copy(confirmAction = NodeActionRequest(node, action)) }
        }
    }

    fun dismissAction() = _state.update { it.copy(confirmAction = null) }

    fun confirmAction() {
        val request = _state.value.confirmAction ?: return
        _state.update { it.copy(confirmAction = null) }
        val node = request.node
        val nodeServerId = node.serverId
        when (request.action) {
            NodeAction.UPDATE_PANEL -> request.panelOffer?.let { panelUpdates.startNode(serverId, it) }
            NodeAction.RESTART_XRAY -> if (nodeServerId != null) act(node.key) {
                status.restartXray(nodeServerId)
                _state.update { it.copy(message = NodesMessage(text = R.string.status_xray_restarted)) }
                load()
            }
            NodeAction.RESTART_PANEL -> if (nodeServerId != null) act(node.key) {
                power.restartPanel(nodeServerId)
                _state.update { it.copy(message = NodesMessage(text = R.string.panel_restarted)) }
                load()
            }
            NodeAction.UPDATE_XRAY -> if (nodeServerId != null) request.xrayOffer?.let { xrayUpdates.start(nodeServerId, it) }
            NodeAction.SYSTEM_UPDATE -> if (nodeServerId != null) systemUpdates.start(nodeServerId)
            NodeAction.REBOOT -> if (nodeServerId != null) power.reboot(nodeServerId)
        }
    }

    // --- one action on every node ---

    fun openBulk() = _state.update { it.copy(bulkChooser = true) }
    fun dismissBulkChooser() = _state.update { it.copy(bulkChooser = false) }
    fun requestBulk(action: NodeAction) = _state.update { it.copy(bulkChooser = false, confirmBulk = action) }
    fun dismissBulk() = _state.update { it.copy(confirmBulk = null) }

    fun confirmBulk() {
        val action = _state.value.confirmBulk ?: return
        _state.update { it.copy(confirmBulk = null) }
        bulk.start(serverId, action)
    }

    fun dismissBulkJob() = bulk.clear(serverId)

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

    /** What each node could update to; a failed check just shows nothing (the menu checks again on tap). */
    private suspend fun checkOffers(refresh: Boolean) {
        val nodes = _state.value.snapshot?.nodes.orEmpty().filter { !it.transitive }
        if (nodes.isEmpty()) return
        coroutineScope {
            val panel = async {
                val check = try {
                    panelUpdates.check(serverId, refresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val offer = (check as? UpdateCheck.Available)?.offer
                offer?.nodes.orEmpty().mapNotNull { target ->
                    target.nodeId?.let { it to NodePanelOffer(it, target.name, target.currentVersion, offer!!.latestVersion) }
                }.toMap()
            }
            val xray = nodes.mapNotNull { it.serverId }.distinct().map { id ->
                async {
                    val check = try {
                        xrayUpdates.check(id, refresh)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    (check as? UpdateCheck.Available)?.offer?.let { id to it }
                }
            }
            val panelOffers = panel.await()
            val xrayOffers = xray.awaitAll().filterNotNull().toMap()
            _state.update { it.copy(panelOffers = panelOffers, xrayOffers = xrayOffers) }
        }
    }

    private companion object {
        const val REFRESH_MS = 10_000L
    }
}

internal val Node.key: String get() = "node:$id:$name"
internal val PendingNode.key: String get() = "server:$serverId"
