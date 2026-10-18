package dev.autorasqr.xuimanager.domain

import dev.autorasqr.xuimanager.domain.model.InboundConfig
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundSecurity
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.usecase.InboundMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundMatrixTest {

    @Test
    fun `reality only for vless and trojan on tcp, xhttp and grpc`() {
        listOf(InboundProtocol.VLESS, InboundProtocol.TROJAN).forEach { protocol ->
            assertEquals(InboundSecurity.REALITY, InboundMatrix.security(protocol))
            assertEquals(listOf(InboundTransport.TCP, InboundTransport.XHTTP, InboundTransport.GRPC), InboundMatrix.transports(protocol))
        }
        assertEquals(InboundSecurity.NONE, InboundMatrix.security(InboundProtocol.VMESS))
        assertTrue(InboundTransport.WS in InboundMatrix.transports(InboundProtocol.VMESS))
        assertEquals(listOf(InboundTransport.TCP), InboundMatrix.transports(InboundProtocol.SHADOWSOCKS))
    }

    @Test
    fun `vision only for vless over tcp`() {
        assertTrue(InboundMatrix.supportsVision(InboundProtocol.VLESS, InboundTransport.TCP))
        assertFalse(InboundMatrix.supportsVision(InboundProtocol.VLESS, InboundTransport.XHTTP))
        assertFalse(InboundMatrix.supportsVision(InboundProtocol.TROJAN, InboundTransport.TCP))
    }

    @Test
    fun validation() {
        val ok = InboundConfig(InboundProtocol.VLESS, InboundTransport.GRPC, 8443, "www.microsoft.com")
        assertNull(InboundMatrix.validate(ok, sshPort = 22))
        assertEquals(ValidationField.TRANSPORT, InboundMatrix.validate(ok.copy(transport = InboundTransport.WS), 22))
        assertEquals(ValidationField.INBOUND_PORT, InboundMatrix.validate(ok.copy(port = 22), 22))
        assertEquals(ValidationField.INBOUND_PORT, InboundMatrix.validate(ok.copy(port = 70000), 22))
        assertEquals(ValidationField.SNI, InboundMatrix.validate(ok.copy(sni = null), 22))
        assertEquals(ValidationField.SNI, InboundMatrix.validate(ok.copy(sni = "1.2.3.4"), 22))
        assertNull(InboundMatrix.validate(InboundConfig(InboundProtocol.VMESS, InboundTransport.WS, 8080, null), 22))
        assertNull(InboundMatrix.validate(InboundConfig(InboundProtocol.SHADOWSOCKS, InboundTransport.TCP, 8388, null), 22))
    }
}
