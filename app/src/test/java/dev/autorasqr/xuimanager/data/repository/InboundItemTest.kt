package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.panel.PanelInbound
import dev.autorasqr.xuimanager.data.panel.RealityInfo
import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundItemTest {
    private fun server(id: Long, domain: String?, selfstealPort: Int?, nodeId: Int? = null) = Server(
        id = id, name = "s$id", host = "10.0.0.$id", sshPort = 22, sshUser = "root", authType = SshAuthType.PASSWORD,
        hostKey = null, createdAt = 0, updatedAt = 0,
        panel = PanelInfo("v3.10.2", 2053, "/", "admin", "https", 2096, "/sub/", null, 0, selfstealPort = selfstealPort),
        domain = domain, masterServerId = if (nodeId != null) 1 else null, nodeId = nodeId,
    )

    private fun selfSteal(port: Int, sni: String, nodeId: Int? = null) = PanelInbound(
        id = 5, remark = "selfsteal", port = 443, protocol = "vless", network = "tcp", security = "reality",
        reality = RealityInfo("127.0.0.1:$port", listOf(sni), "chrome"), nodeId = nodeId,
    )

    private val master = server(1, "vpn.example.com", 8443)
    private val node = server(2, "de.example.com", 8443, nodeId = 3)

    @Test
    fun `the main server's self-steal inbound uses its own site`() {
        val item = selfSteal(8443, "vpn.example.com").toItem(master, null)
        assertTrue(item.selfSteal)
        assertEquals(1L, item.siteServerId)
        assertEquals("vpn.example.com", item.siteDomain)
    }

    @Test
    fun `a node's self-steal inbound uses the node's site`() {
        val item = selfSteal(8443, "de.example.com", nodeId = 3).toItem(master, "germ", node)
        assertTrue(item.selfSteal)
        assertEquals(2L, item.siteServerId)
        assertEquals("de.example.com", item.siteDomain)
    }

    @Test
    fun `a node the app can't reach keeps its loopback target to itself`() {
        val item = selfSteal(8443, "de.example.com", nodeId = 3).toItem(master, "germ", nodeServer = null)
        assertFalse(item.selfSteal)
        assertNull(item.siteServerId)
    }
}
