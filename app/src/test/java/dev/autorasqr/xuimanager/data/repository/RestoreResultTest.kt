package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.deploy.PanelProvisioner
import dev.autorasqr.xuimanager.data.panel.PanelInbound
import dev.autorasqr.xuimanager.domain.model.AppException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreResultTest {

    private val values = mapOf(
        "PANEL_PORT" to "2053",
        "WEB_BASE_PATH" to "/old/",
        "PANEL_VERSION" to "3.10.2",
        "API_TOKEN" to "tok",
        "CERT_HOST" to "VPN.example.com",
        "SELFSTEAL_PORT" to "8443",
    )

    @Test
    fun `parses what restore sh reports`() {
        val result = RestoreResult.parse(values)
        assertEquals(2053, result.panelPort)
        assertEquals("/old/", result.webBasePath)
        assertEquals("v3.10.2", result.panelVersion)
        assertEquals("tok", result.apiToken)
        assertEquals(8443, result.selfstealPort)
        assertEquals("vpn.example.com", result.domain)
    }

    @Test
    fun `an IP certificate means no domain, and empty values mean none`() {
        val result = RestoreResult.parse(values + mapOf("CERT_HOST" to "203.0.113.7", "API_TOKEN" to "", "SELFSTEAL_PORT" to "", "WEB_BASE_PATH" to ""))
        assertNull(result.domain)
        assertNull(result.apiToken)
        assertNull(result.selfstealPort)
        assertEquals("/", result.webBasePath)
    }

    @Test(expected = AppException::class)
    fun `a missing certificate name is an error`() {
        RestoreResult.parse(values - "CERT_HOST")
    }

    @Test
    fun `the default inbound is the one the app created, then the first own one clients can use`() {
        fun inbound(id: Int, remark: String, protocol: String = "vless", nodeId: Int? = null) =
            PanelInbound(id = id, remark = remark, port = 443 + id, protocol = protocol, network = "tcp", security = "reality", nodeId = nodeId)
        val inbounds = listOf(
            inbound(1, "socks", protocol = "socks"),
            inbound(2, "on node", nodeId = 5),
            inbound(3, "mine"),
            inbound(4, DeployConfig.INBOUND_REMARK),
            inbound(5, DeployConfig.SELFSTEAL_REMARK),
        )
        assertEquals(5, PanelProvisioner.defaultInbound(inbounds)?.id)
        assertEquals(4, PanelProvisioner.defaultInbound(inbounds.dropLast(1))?.id)
        assertEquals(3, PanelProvisioner.defaultInbound(inbounds.take(3))?.id)
        assertEquals(2, PanelProvisioner.defaultInbound(inbounds.take(2))?.id)
        assertNull(PanelProvisioner.defaultInbound(inbounds.take(1)))
    }
}
