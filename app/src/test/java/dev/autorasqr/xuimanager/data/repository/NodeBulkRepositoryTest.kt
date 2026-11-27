package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.Node
import dev.autorasqr.xuimanager.domain.model.NodeAction
import dev.autorasqr.xuimanager.domain.model.NodeChoice
import dev.autorasqr.xuimanager.domain.model.NodeDraft
import dev.autorasqr.xuimanager.domain.model.NodeInbound
import dev.autorasqr.xuimanager.domain.model.NodePanelOffer
import dev.autorasqr.xuimanager.domain.model.NodeStatus
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.NodesSnapshot
import dev.autorasqr.xuimanager.domain.model.PanelUpdateOffer
import dev.autorasqr.xuimanager.domain.model.PanelUpdateProgress
import dev.autorasqr.xuimanager.domain.model.PanelUpdateTarget
import dev.autorasqr.xuimanager.domain.model.RebootProgress
import dev.autorasqr.xuimanager.domain.model.ServerStatus
import dev.autorasqr.xuimanager.domain.model.SystemUpdateProgress
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import dev.autorasqr.xuimanager.domain.model.XrayUpdateProgress
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.domain.repository.PanelStatusRepository
import dev.autorasqr.xuimanager.domain.repository.PanelUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.ServerPowerRepository
import dev.autorasqr.xuimanager.domain.repository.SystemUpdateRepository
import dev.autorasqr.xuimanager.domain.repository.XrayUpdateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeBulkRepositoryTest {

    private val calls = mutableListOf<String>()

    private fun node(id: Int, serverId: Long?, enabled: Boolean = true, transitive: Boolean = false) = Node(
        id = id, name = "n$id", remark = "", scheme = "https", address = "10.0.0.$id", port = 2053, basePath = "/",
        enabled = enabled, status = NodeStatus.ONLINE, latencyMs = 1, cpuPercent = 0.0, memPercent = 0.0, uptimeSeconds = 0,
        xrayState = "running", xrayVersion = "26.1.1", xrayError = "", panelVersion = "v3.10.2", lastError = "",
        inboundCount = 0, clientCount = 0, onlineCount = 0, hasApiToken = true, tlsVerifyMode = "verify", pinnedCertSha256 = "",
        inboundSyncMode = "all", inboundTags = emptyList(), outboundTag = "", allowPrivateAddress = false,
        transitive = transitive, serverId = serverId,
    )

    private inner class Nodes(val list: List<Node>) : NodeRepository {
        override suspend fun supported(serverId: Long) = true
        override suspend fun load(serverId: Long) = NodesSnapshot(list, emptyList())
        override suspend fun get(serverId: Long, nodeId: Int) = list.firstOrNull { it.id == nodeId }
        override suspend fun save(serverId: Long, draft: NodeDraft) = error("unused")
        override suspend fun test(serverId: Long, draft: NodeDraft): NodeTestResult = error("unused")
        override suspend fun fetchPin(serverId: Long, draft: NodeDraft): String = error("unused")
        override suspend fun remoteInbounds(serverId: Long, draft: NodeDraft): List<NodeInbound> = error("unused")
        override suspend fun outboundTags(serverId: Long): List<String> = error("unused")
        override suspend fun setEnabled(serverId: Long, nodeId: Int, enabled: Boolean) = error("unused")
        override suspend fun probe(serverId: Long, nodeId: Int): NodeTestResult = error("unused")
        override suspend fun delete(serverId: Long, nodeId: Int) = error("unused")
        override suspend fun register(masterId: Long, nodeServerId: Long): Int = error("unused")
        override suspend fun choices(serverId: Long): List<NodeChoice> = error("unused")
        override suspend fun webCert(serverId: Long, nodeId: Int): Pair<String, String>? = error("unused")
    }

    private inner class Status(val failOn: Set<Long> = emptySet()) : PanelStatusRepository {
        override suspend fun status(serverId: Long): ServerStatus = error("unused")
        override suspend fun restartXray(serverId: Long) {
            calls += "xray:$serverId"
            if (serverId in failOn) throw AppException(AppError.PanelError("boom"))
        }
    }

    private inner class Power : ServerPowerRepository {
        val flows = mutableMapOf<Long, MutableStateFlow<RebootProgress?>>()
        override suspend fun restartPanel(serverId: Long) {
            calls += "panel:$serverId"
        }
        override fun reboot(serverId: Long) {
            calls += "reboot:$serverId"
            rebootProgress(serverId)
            flows.getValue(serverId).value = RebootProgress(DeployState.SUCCEEDED, emptyList(), null)
        }
        override fun rebootProgress(serverId: Long): StateFlow<RebootProgress?> = flows.getOrPut(serverId) { MutableStateFlow(null) }
        override fun clearRebootProgress(serverId: Long) = Unit
    }

    private inner class Xray(val offers: Map<Long, XrayUpdateOffer>) : XrayUpdateRepository {
        val flows = mutableMapOf<Long, MutableStateFlow<XrayUpdateProgress?>>()
        override suspend fun check(serverId: Long, refresh: Boolean): UpdateCheck<XrayUpdateOffer> =
            offers[serverId]?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
        override fun shouldPrompt(serverId: Long) = false
        override fun markPrompted(serverId: Long) = Unit
        override fun start(serverId: Long, offer: XrayUpdateOffer) {
            calls += "xrayUpdate:$serverId"
            progress(serverId)
            flows.getValue(serverId).value = XrayUpdateProgress(offer.latestVersion, DeployState.SUCCEEDED, emptyList(), null)
        }
        override fun progress(serverId: Long): StateFlow<XrayUpdateProgress?> = flows.getOrPut(serverId) { MutableStateFlow(null) }
        override fun clearProgress(serverId: Long) = Unit
    }

    private inner class System : SystemUpdateRepository {
        val flows = mutableMapOf<Long, MutableStateFlow<SystemUpdateProgress?>>()
        override fun start(serverId: Long) {
            calls += "sys:$serverId"
            progress(serverId)
            flows.getValue(serverId).value = SystemUpdateProgress(DeployState.SUCCEEDED, emptyList(), null)
        }
        override fun progress(serverId: Long): StateFlow<SystemUpdateProgress?> = flows.getOrPut(serverId) { MutableStateFlow(null) }
        override fun clearProgress(serverId: Long) = Unit
    }

    private inner class Panels(val offer: PanelUpdateOffer?) : PanelUpdateRepository {
        var started: PanelUpdateOffer? = null
        val flow = MutableStateFlow<PanelUpdateProgress?>(null)
        override suspend fun check(serverId: Long, refresh: Boolean): UpdateCheck<PanelUpdateOffer> =
            offer?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
        override fun shouldPrompt(serverId: Long) = false
        override fun markPrompted(serverId: Long) = Unit
        override fun start(serverId: Long, offer: PanelUpdateOffer) {
            started = offer
            flow.value = PanelUpdateProgress(offer.latestVersion, DeployState.SUCCEEDED, listOf("updated"), null)
        }
        override fun progress(serverId: Long): StateFlow<PanelUpdateProgress?> = flow
        override fun clearProgress(serverId: Long) = Unit
        override fun startNode(masterId: Long, offer: NodePanelOffer) = Unit
        override fun nodeProgress(masterId: Long, nodeId: Int): StateFlow<PanelUpdateProgress?> = MutableStateFlow(null)
        override fun clearNodeProgress(masterId: Long, nodeId: Int) = Unit
    }

    private fun TestScope.repo(
        nodes: List<Node>,
        status: Status = Status(),
        xray: Xray = Xray(emptyMap()),
        panels: Panels = Panels(null),
    ) = NodeBulkRepositoryImpl(Nodes(nodes), panels, status, Power(), xray, System(), backgroundScope)

    private val nodes = listOf(
        node(1, serverId = 11),
        node(2, serverId = null),
        node(3, serverId = 13),
        node(4, serverId = 14, enabled = false),
        node(5, serverId = 15, transitive = true),
    )

    @Test
    fun `runs on enabled nodes with SSH one after another and skips the rest`() = runTest {
        val repo = repo(nodes)
        repo.start(MASTER, NodeAction.REBOOT)
        runCurrent()
        assertEquals(listOf("reboot:11", "reboot:13"), calls)
        val done = repo.progress(MASTER).value!!
        assertEquals(DeployState.SUCCEEDED, done.state)
        assertTrue(done.log.any { it.startsWith("n2:") && "skipped" in it })
    }

    @Test
    fun `an error on one node does not stop the others and fails the run naming it`() = runTest {
        val repo = repo(nodes, status = Status(failOn = setOf(11)))
        repo.start(MASTER, NodeAction.RESTART_XRAY)
        runCurrent()
        assertEquals(listOf("xray:11", "xray:13"), calls)
        val done = repo.progress(MASTER).value!!
        assertEquals(DeployState.FAILED, done.state)
        assertEquals(AppError.NodeActionsFailed("n1"), done.error)
    }

    @Test
    fun `Xray is only updated where a newer release exists`() = runTest {
        val repo = repo(nodes, xray = Xray(mapOf(13L to XrayUpdateOffer("26.1.1", "26.2.0"))))
        repo.start(MASTER, NodeAction.UPDATE_XRAY)
        runCurrent()
        assertEquals(listOf("xrayUpdate:13"), calls)
        assertEquals(DeployState.SUCCEEDED, repo.progress(MASTER).value!!.state)
    }

    @Test
    fun `system update runs on each node`() = runTest {
        val repo = repo(nodes)
        repo.start(MASTER, NodeAction.SYSTEM_UPDATE)
        runCurrent()
        assertEquals(listOf("sys:11", "sys:13"), calls)
    }

    @Test
    fun `3x-ui goes through the main panel for every outdated node but not the main panel itself`() = runTest {
        val offer = PanelUpdateOffer(
            latestVersion = "v3.11.0",
            master = PanelUpdateTarget("master", "v3.10.2"),
            nodes = listOf(PanelUpdateTarget("n1", "v3.10.2", 1), PanelUpdateTarget("n2", "v3.10.2", 2)),
        )
        val panels = Panels(offer)
        val repo = repo(nodes, panels = panels)
        repo.start(MASTER, NodeAction.UPDATE_PANEL)
        runCurrent()
        assertEquals(offer.copy(master = null), panels.started)
        assertEquals(DeployState.SUCCEEDED, repo.progress(MASTER).value!!.state)
    }

    @Test
    fun `3x-ui up to date on every node starts nothing`() = runTest {
        val panels = Panels(PanelUpdateOffer("v3.11.0", PanelUpdateTarget("master", "v3.10.2"), emptyList()))
        val repo = repo(nodes, panels = panels)
        repo.start(MASTER, NodeAction.UPDATE_PANEL)
        runCurrent()
        assertEquals(null, panels.started)
        assertEquals(DeployState.SUCCEEDED, repo.progress(MASTER).value!!.state)
    }

    private companion object {
        const val MASTER = 1L
    }
}
