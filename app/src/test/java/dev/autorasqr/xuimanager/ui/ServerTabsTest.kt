package dev.autorasqr.xuimanager.ui

import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.UiStyle
import dev.autorasqr.xuimanager.ui.servers.ServerTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTabsTest {
    private fun server(version: String?) = Server(
        id = 1, name = "fin2", host = "203.0.113.1", sshPort = 22, sshUser = "root", authType = SshAuthType.PASSWORD,
        hostKey = null, createdAt = 0, updatedAt = 0,
        panel = version?.let { PanelInfo(it, 30000, "path", "admin", "https", 40000, "/sub/", 1, 0) },
    )

    @Test
    fun `v3 panel gets every tab`() {
        assertEquals(ServerTab.entries, ServerTab.tabsFor(server("v3.10.2")))
        assertTrue(ServerTab.isV3(server("v3.10.2")))
    }

    @Test
    fun `v2 panel has no nodes tab`() {
        assertEquals(
            listOf(ServerTab.OVERVIEW, ServerTab.INBOUNDS, ServerTab.CLIENTS, ServerTab.MORE),
            ServerTab.tabsFor(server("v2.6.0")),
        )
        assertFalse(ServerTab.isV3(server("v2.6.0")))
    }

    @Test
    fun `no tabs without a panel, for a node, or before the server loads`() {
        assertTrue(ServerTab.tabsFor(null).isEmpty())
        assertTrue(ServerTab.tabsFor(server(null)).isEmpty())
        assertTrue(ServerTab.tabsFor(server("v3.10.2").copy(masterServerId = 2, nodeId = 5)).isEmpty())
    }

    @Test
    fun `stored style falls back to the default`() {
        assertEquals(UiStyle.ONE_UI, UiStyle.parse("ONE_UI"))
        assertEquals(UiStyle.MATERIAL_YOU, UiStyle.parse(null))
        assertEquals(UiStyle.MATERIAL_YOU, UiStyle.parse("CUPERTINO"))
    }
}
