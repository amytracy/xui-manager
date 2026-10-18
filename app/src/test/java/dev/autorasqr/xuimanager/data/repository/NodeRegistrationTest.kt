package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.panel.PanelNode
import dev.autorasqr.xuimanager.data.repository.NodeRepositoryImpl.Companion.toMutation
import dev.autorasqr.xuimanager.domain.model.NodeDraft
import dev.autorasqr.xuimanager.domain.model.PanelInfo
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.ui.nodes.NodeEditViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NodeRegistrationTest {
    private fun node(id: Int, name: String) = PanelNode(
        id = id, name = name, remark = "", scheme = "https", address = "n$id.example.com", port = 2053, basePath = "/",
        enabled = true, status = "online", latencyMs = 1, cpuPercent = 0.0, memPercent = 0.0, uptimeSeconds = 0, xrayState = "",
        xrayVersion = "", xrayError = "", panelVersion = "", lastError = "", lastHeartbeat = 0, inboundCount = 0, clientCount = 0,
        onlineCount = 0, hasApiToken = true, tlsVerifyMode = "verify", pinnedCertSha256 = "", inboundSyncMode = "all",
        inboundTags = emptyList(), outboundTag = "", allowPrivateAddress = false, transitive = false,
    )

    @Test
    fun `a deployed node server registers over its certificate host with its own token`() {
        val server = Server(
            id = 7, name = "Frankfurt", host = "203.0.113.9", sshPort = 22, sshUser = "root", authType = SshAuthType.PASSWORD,
            hostKey = null, createdAt = 0, updatedAt = 0, domain = "fra.example.com", masterServerId = 1,
            panel = PanelInfo("v3.10.2", 31234, "/Xy9path/", "admin", "https", 40000, "/sub/", 1, 0, host = "fra.example.com"),
        )
        val mutation = NodeRepositoryImpl.registration(server, 31234, "/Xy9path/", "tok", "Frankfurt")
        assertEquals("fra.example.com", mutation.address)
        assertEquals(31234, mutation.port)
        assertEquals("/Xy9path/", mutation.basePath)
        assertEquals("https", mutation.scheme)
        assertEquals("verify", mutation.tlsVerifyMode)
        assertEquals("all", mutation.inboundSyncMode)
        assertEquals("tok", mutation.apiToken)
        assertFalse("the token never shows up in logs", mutation.toString().contains("tok,"))
    }

    @Test
    fun `node names stay unique on the panel`() {
        assertEquals("Frankfurt", NodeRepositoryImpl.uniqueName("Frankfurt", listOf(node(1, "Amsterdam"))))
        assertEquals("Frankfurt-2", NodeRepositoryImpl.uniqueName("Frankfurt", listOf(node(1, "frankfurt"))))
        assertEquals("Frankfurt-3", NodeRepositoryImpl.uniqueName(" Frankfurt ", listOf(node(1, "Frankfurt"), node(2, "Frankfurt-2"))))
        assertEquals("node", NodeRepositoryImpl.uniqueName("  ", emptyList()))
    }

    @Test
    fun `outbound choices skip blackholes and include balancers`() {
        val config = Json.parseToJsonElement(
            """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"blocked","protocol":"blackhole"},{"tag":"warp","protocol":"wireguard"},{"protocol":"freedom"}],
               "routing":{"balancers":[{"tag":"bal-1"}]}}""",
        ).jsonObject
        assertEquals(listOf("direct", "warp", "bal-1"), NodeRepositoryImpl.outboundTagsOf(config))
    }

    @Test
    fun `a blank token in the form keeps the stored one`() {
        val draft = NodeDraft(id = 4, name = "n", address = "n.example.com", port = 2053, apiToken = "  ")
        assertNull(draft.toMutation().apiToken)
        assertEquals(4, draft.toMutation().id)
        assertEquals("abc", draft.copy(apiToken = " abc ").toMutation().apiToken)
    }

    @Test
    fun `a pasted panel url is reduced to the host`() {
        assertEquals("node.example.com", NodeEditViewModel.normalizeAddress(" https://node.example.com:2053/path/ "))
        assertEquals("203.0.113.5", NodeEditViewModel.normalizeAddress("203.0.113.5:2053"))
        assertEquals("[2001:db8::1]", NodeEditViewModel.normalizeAddress("https://[2001:db8::1]:2053/"))
        assertEquals("2001:db8::1", NodeEditViewModel.normalizeAddress("2001:db8::1"))
    }
}
