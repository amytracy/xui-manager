package dev.autorasqr.xuimanager.ui

import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.ui.home.HomeViewModel
import dev.autorasqr.xuimanager.ui.servers.ServerEditViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePickTest {
    private fun server(id: Long, deployed: Boolean, domain: String? = null) = Server(
        id = id, name = "s$id", host = "203.0.113.$id", sshPort = 22, sshUser = "root", authType = SshAuthType.PASSWORD,
        hostKey = null, createdAt = 0, updatedAt = 0, domain = domain,
        panel = if (deployed) PanelInfo("v3.10.2", 30000, "path", "admin", "https", 40000, "/sub/", 1, 0) else null,
    )

    @Test
    fun `deployed server wins, then the oldest`() {
        assertNull(HomeViewModel.pick(emptyList()))
        assertEquals(2L, HomeViewModel.pick(listOf(server(1, false), server(3, true), server(2, true)))?.id)
        assertEquals(1L, HomeViewModel.pick(listOf(server(4, false), server(1, false)))?.id)
    }

    @Test
    fun `servers deployed as nodes are never the home server`() {
        val node = server(1, true).copy(masterServerId = 2, nodeId = 5)
        assertEquals(2L, HomeViewModel.pick(listOf(node, server(2, true)))?.id)
        assertEquals(3L, HomeViewModel.pick(listOf(node, server(3, false)))?.id)
        assertNull(HomeViewModel.pick(listOf(node)))
    }

    @Test
    fun `pasted domain url is reduced to the host name`() {
        assertEquals("vpn.example.com", ServerEditViewModel.normalizeDomain(" https://VPN.Example.com:8443/path "))
        assertEquals("vpn.example.com", ServerEditViewModel.normalizeDomain("vpn.example.com."))
        assertEquals("", ServerEditViewModel.normalizeDomain(""))
    }

    @Test
    fun `panel host prefers the certificate host, then the domain`() {
        assertEquals("203.0.113.1", server(1, false).panelHost)
        assertEquals("vpn.example.com", server(1, false, "vpn.example.com").panelHost)
        assertEquals("vpn.example.com", server(1, false, "vpn.example.com").deployHost)
        // Deployed before the certificate host was recorded: it was always the SSH host.
        val legacy = server(1, true, "vpn.example.com")
        assertEquals("203.0.113.1", legacy.panelHost)
        val byDomain = legacy.copy(panel = legacy.panel!!.copy(host = "vpn.example.com"))
        assertEquals("vpn.example.com", byDomain.panelHost)
    }
}
