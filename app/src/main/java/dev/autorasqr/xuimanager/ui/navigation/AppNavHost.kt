package dev.autorasqr.xuimanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.autorasqr.xuimanager.ui.appearance.AppearanceScreen
import dev.autorasqr.xuimanager.ui.clients.ClientsScreen
import dev.autorasqr.xuimanager.ui.deploy.DeployScreen
import dev.autorasqr.xuimanager.ui.home.HomeScreen
import dev.autorasqr.xuimanager.ui.inbounds.InboundsScreen
import dev.autorasqr.xuimanager.ui.inbounds.editor.InboundEditorScreen
import dev.autorasqr.xuimanager.ui.nodes.NodeEditScreen
import dev.autorasqr.xuimanager.ui.nodes.NodesScreen
import dev.autorasqr.xuimanager.ui.panel.PanelScreen
import dev.autorasqr.xuimanager.ui.routing.RoutingScreen
import dev.autorasqr.xuimanager.ui.servers.MoreScreen
import dev.autorasqr.xuimanager.ui.servers.RestoreScreen
import dev.autorasqr.xuimanager.ui.servers.ServerDetailScreen
import dev.autorasqr.xuimanager.ui.servers.ServerEditScreen
import dev.autorasqr.xuimanager.ui.servers.ServerHomeScreen
import dev.autorasqr.xuimanager.ui.subscription.SubscriptionScreen
import dev.autorasqr.xuimanager.ui.servers.UninstallScreen
import kotlinx.serialization.Serializable

/** Start destination; forwards to the server screen or to onboarding. */
@Serializable
object HomeRoute

/**
 * serverId = 0 creates a new server; with masterServerId it is deployed as a node of that server's panel. With
 * restoreNodeId it is that node, already on the master panel, and is restored over SSH instead ([host] and [name]
 * prefill the form).
 */
@Serializable
data class ServerEditRoute(
    val serverId: Long = 0L,
    val masterServerId: Long = 0L,
    val restoreNodeId: Int = 0,
    val host: String = "",
    val name: String = "",
)

@Serializable
data class ServerDetailRoute(val serverId: Long)

@Serializable
data class DeployRoute(val serverId: Long)

@Serializable
data class ClientsRoute(val serverId: Long)

@Serializable
data class PanelRoute(val serverId: Long)

@Serializable
data class InboundsRoute(val serverId: Long)

@Serializable
data class RoutingRoute(val serverId: Long)

@Serializable
data class NodesRoute(val serverId: Long)

@Serializable
data class SubscriptionRoute(val serverId: Long)

/** nodeId = 0 adds a node to the panel of server [serverId]. */
@Serializable
data class NodeEditRoute(val serverId: Long, val nodeId: Int = 0)

/** Connects the app to the panel already set up on the server, without deploying it again. */
@Serializable
data class RestoreRoute(val serverId: Long)

/** Removes everything the app installed from the server (and its node servers), then the servers from the app. */
@Serializable
data class UninstallRoute(val serverId: Long)

/** inboundId = 0 creates a new inbound. */
@Serializable
data class InboundEditorRoute(val serverId: Long, val inboundId: Int = 0)

/** The "More" tab of the server screen. */
@Serializable
data class MoreRoute(val serverId: Long)

@Serializable
object AppearanceRoute

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOnboarding = { nav.navigate(ServerEditRoute()) { popUpTo<HomeRoute> { inclusive = true } } },
                onServer = { id -> nav.navigate(ServerDetailRoute(id)) { popUpTo<HomeRoute> { inclusive = true } } },
            )
        }
        composable<ServerEditRoute> { entry ->
            // Onboarding is the root screen when there is no server yet; editing is opened from the server screen.
            val canGoBack = nav.previousBackStackEntry != null
            val route = entry.toRoute<ServerEditRoute>()
            val addingNode = route.masterServerId > 0
            ServerEditScreen(
                onBack = if (canGoBack) ({ nav.popBackStack() }) else null,
                onCreated = { id ->
                    // A new node server goes straight to its deployment, which registers it on the master afterwards;
                    // a node that is already on the master is restored instead.
                    if (route.restoreNodeId > 0) {
                        nav.navigate(RestoreRoute(id)) { popUpTo<ServerEditRoute> { inclusive = true } }
                    } else if (addingNode) {
                        nav.navigate(DeployRoute(id)) { popUpTo<ServerEditRoute> { inclusive = true } }
                    } else {
                        nav.navigate(ServerDetailRoute(id)) { popUpTo<ServerEditRoute> { inclusive = true } }
                    }
                },
                onUpdated = { nav.popBackStack() },
                onDeleted = { wasNode -> nav.afterServerDeleted(wasNode) },
                onUninstall = { id -> nav.navigate(UninstallRoute(id)) },
            )
        }
        composable<RestoreRoute> {
            RestoreScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable<UninstallRoute> {
            UninstallScreen(
                onBack = { nav.popBackStack() },
                onDone = { wasNode -> nav.afterServerDeleted(wasNode) },
            )
        }
        composable<ServerDetailRoute> {
            // The home server is the root; a node's server screen is opened from the node list.
            val canGoBack = remember { nav.previousBackStackEntry != null }
            // Tabs of the server screen; everything they open goes full screen over them, on the app's own stack.
            ServerHomeScreen(
                tabs = {
                    composable<ServerDetailRoute> {
                        ServerDetailScreen(
                            onEdit = { id -> nav.navigate(ServerEditRoute(id)) },
                            onDeploy = { id -> nav.navigate(DeployRoute(id)) },
                            onOpenPanel = { id -> nav.navigate(PanelRoute(id)) },
                            onRestore = { id -> nav.navigate(RestoreRoute(id)) },
                            onBack = if (canGoBack) ({ nav.popBackStack() }) else null,
                        )
                    }
                    composable<InboundsRoute> { entry ->
                        val serverId = entry.toRoute<InboundsRoute>().serverId
                        InboundsScreen(
                            onBack = null,
                            onEditor = { inboundId -> nav.navigate(InboundEditorRoute(serverId, inboundId ?: 0)) },
                        )
                    }
                    composable<ClientsRoute> {
                        ClientsScreen(onBack = null)
                    }
                    composable<NodesRoute> {
                        NodesDestination(nav, onBack = null)
                    }
                    composable<MoreRoute> {
                        MoreScreen(
                            onRouting = { id -> nav.navigate(RoutingRoute(id)) },
                            onSubscription = { id -> nav.navigate(SubscriptionRoute(id)) },
                            onOpenPanel = { id -> nav.navigate(PanelRoute(id)) },
                            onServerSettings = { id -> nav.navigate(ServerEditRoute(id)) },
                            onAppearance = { nav.navigate(AppearanceRoute) },
                        )
                    }
                },
            )
        }
        composable<NodesRoute> {
            NodesDestination(nav, onBack = { nav.popBackStack() })
        }
        composable<AppearanceRoute> {
            AppearanceScreen(onBack = { nav.popBackStack() })
        }
        composable<NodeEditRoute> {
            NodeEditScreen(onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable<InboundEditorRoute> {
            InboundEditorScreen(onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable<RoutingRoute> {
            RoutingScreen(onBack = { nav.popBackStack() })
        }
        composable<SubscriptionRoute> {
            SubscriptionScreen(onBack = { nav.popBackStack() })
        }
        composable<PanelRoute> {
            PanelScreen(onBack = { nav.popBackStack() })
        }
        composable<DeployRoute> {
            DeployScreen(
                onBack = { nav.popBackStack() },
                onClients = { id ->
                    nav.navigate(ClientsRoute(id)) { popUpTo<DeployRoute> { inclusive = true } }
                },
                onNodes = { masterId ->
                    // Back to the master's server screen, which is still on its Nodes tab.
                    if (!nav.popBackStack(ServerDetailRoute(masterId), inclusive = false) && !nav.popBackStack<NodesRoute>(inclusive = false)) {
                        nav.navigate(NodesRoute(masterId)) { popUpTo<DeployRoute> { inclusive = true } }
                    }
                },
            )
        }
        composable<ClientsRoute> {
            ClientsScreen(onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun NodesDestination(nav: NavHostController, onBack: (() -> Unit)?) {
    NodesScreen(
        onBack = onBack,
        onAddServer = { masterId -> nav.navigate(ServerEditRoute(masterServerId = masterId)) },
        onAddPanel = { masterId -> nav.navigate(NodeEditRoute(masterId)) },
        onEdit = { masterId, nodeId -> nav.navigate(NodeEditRoute(masterId, nodeId)) },
        onServer = { id -> nav.navigate(ServerDetailRoute(id)) },
        onDeploy = { id -> nav.navigate(DeployRoute(id)) },
        onConnectSsh = { masterId, node ->
            nav.navigate(ServerEditRoute(masterServerId = masterId, restoreNodeId = node.id, host = node.address, name = node.name))
        },
    )
}

/** Leaves the screens of a server that is gone from the app. */
private fun NavHostController.afterServerDeleted(wasNode: Boolean) {
    if (wasNode) {
        // Back to the master's Nodes tab (or the node list), past the deleted node's own server screen, which is the
        // topmost one: node settings are only opened from there.
        if (!popBackStack<NodesRoute>(inclusive = false) && !popBackStack<ServerDetailRoute>(inclusive = true)) popBackStack()
    } else {
        navigate(HomeRoute) { popUpTo(graph.id) { inclusive = true } }
    }
}
