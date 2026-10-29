package dev.autorasqr.xuimanager.ui.servers

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.ui.navigation.ClientsRoute
import dev.autorasqr.xuimanager.ui.navigation.InboundsRoute
import dev.autorasqr.xuimanager.ui.navigation.MoreRoute
import dev.autorasqr.xuimanager.ui.navigation.NodesRoute
import dev.autorasqr.xuimanager.ui.navigation.ServerDetailRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.reflect.KClass

/** Bottom tabs of the server screen. */
enum class ServerTab(@param:StringRes val label: Int, val route: KClass<*>) {
    OVERVIEW(R.string.tab_overview, ServerDetailRoute::class),
    INBOUNDS(R.string.action_inbounds, InboundsRoute::class),
    CLIENTS(R.string.action_clients, ClientsRoute::class),
    NODES(R.string.action_nodes, NodesRoute::class),
    MORE(R.string.tab_more, MoreRoute::class),
    ;

    fun routeFor(serverId: Long): Any = when (this) {
        OVERVIEW -> ServerDetailRoute(serverId)
        INBOUNDS -> InboundsRoute(serverId)
        CLIENTS -> ClientsRoute(serverId)
        NODES -> NodesRoute(serverId)
        MORE -> MoreRoute(serverId)
    }

    companion object {
        /**
         * Tabs only exist for a server with a panel the app manages: without one there is only the overview (deploy or
         * restore), and a node's inbounds and clients live on the main panel.
         */
        fun tabsFor(server: Server?): List<ServerTab> {
            if (server?.panel == null || server.isNode) return emptyList()
            return if (isV3(server)) entries else entries - NODES
        }

        /** Nodes and the subscription settings screen need 3x-ui v3. */
        fun isV3(server: Server?): Boolean = server?.panel?.let { (PanelVersion.major(it.version) ?: 0) >= 3 } == true
    }
}

@HiltViewModel
class ServerHomeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    serverRepository: ServerRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<ServerDetailRoute>().serverId

    val server: StateFlow<Server?> = serverRepository.observeServer(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
