package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.panel.PanelNode
import dev.autorasqr.xuimanager.data.panel.PanelUpdateInfo
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.domain.model.PanelUpdateTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelUpdateOfferTest {

    private fun node(id: Int, version: String, status: String = "online", enabled: Boolean = true, transitive: Boolean = false) = PanelNode(
        id = id, name = "node$id", remark = "", scheme = "https", address = "10.0.0.$id", port = 2053, basePath = "/",
        enabled = enabled, status = status, latencyMs = 0, cpuPercent = 0.0, memPercent = 0.0, uptimeSeconds = 0,
        xrayState = "running", xrayVersion = "", xrayError = "", panelVersion = version, lastError = "", lastHeartbeat = 0,
        inboundCount = 0, clientCount = 0, onlineCount = 0, hasApiToken = true, tlsVerifyMode = "verify", pinnedCertSha256 = "",
        inboundSyncMode = "all", inboundTags = emptyList(), outboundTag = "", allowPrivateAddress = false, transitive = transitive,
    )

    @Test
    fun `versions compare part by part`() {
        assertEquals(0, PanelVersion.compare("v3.10.2", "3.10.2"))
        assertEquals(0, PanelVersion.compare("3.10", "v3.10.0"))
        assertEquals(-1, PanelVersion.compare("v3.9.9", "v3.10.0"))
        assertEquals(1, PanelVersion.compare("v3.11.0", "v3.10.2"))
        assertEquals(1, PanelVersion.compare("4.0.0-beta", "3.99"))
        assertNull(PanelVersion.compare("", "v3.10.2"))
        assertNull(PanelVersion.compare("dev", "v3.10.2"))
    }

    @Test
    fun `offers the main panel and only reachable, directly managed nodes that are behind`() {
        val offer = PanelUpdateRepositoryImpl.offerFrom(
            masterName = "fintest",
            info = PanelUpdateInfo("3.10.2", "v3.11.0", updateAvailable = true),
            nodes = listOf(
                node(1, "v3.10.2"),
                node(2, "v3.11.0"),
                node(3, "v3.9.0", status = "offline"),
                node(4, "v3.9.0", enabled = false),
                node(5, "v3.9.0", transitive = true),
                node(6, ""),
            ),
        )!!
        assertEquals("v3.11.0", offer.latestVersion)
        assertEquals(PanelUpdateTarget("fintest", "v3.10.2"), offer.master)
        assertEquals(listOf(PanelUpdateTarget("node1", "v3.10.2", 1)), offer.nodes)
    }

    @Test
    fun `a current main panel still offers its outdated nodes`() {
        val offer = PanelUpdateRepositoryImpl.offerFrom("fintest", PanelUpdateInfo("v3.11.0", "v3.11.0", false), listOf(node(1, "v3.10.2")))!!
        assertNull(offer.master)
        assertEquals(listOf(1), offer.nodes.map { it.nodeId })
    }

    @Test
    fun `nothing to offer when everything is current or the latest release is unknown`() {
        assertNull(PanelUpdateRepositoryImpl.offerFrom("fintest", PanelUpdateInfo("v3.11.0", "v3.11.0", false), listOf(node(1, "v3.11.0"))))
        assertNull(PanelUpdateRepositoryImpl.offerFrom("fintest", PanelUpdateInfo("v3.10.2", "", true), listOf(node(1, "v3.10.2"))))
    }
}
