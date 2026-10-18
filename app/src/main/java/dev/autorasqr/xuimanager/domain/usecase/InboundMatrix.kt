package dev.autorasqr.xuimanager.domain.usecase

import dev.autorasqr.xuimanager.domain.model.InboundConfig
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundSecurity
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import dev.autorasqr.xuimanager.domain.model.ValidationField

/**
 * Which protocol/transport/security combinations manual mode offers. Mirrors 3x-ui's protocol-capabilities:
 * REALITY works for VLESS and Trojan on TCP, gRPC and XHTTP only. VLESS and Trojan without REALITY would send
 * credentials in clear text, so they are not offered; VMess and Shadowsocks 2022 encrypt by themselves.
 */
object InboundMatrix {
    fun transports(protocol: InboundProtocol): List<InboundTransport> = when (protocol) {
        InboundProtocol.VLESS, InboundProtocol.TROJAN ->
            listOf(InboundTransport.TCP, InboundTransport.XHTTP, InboundTransport.GRPC)
        InboundProtocol.VMESS ->
            listOf(InboundTransport.TCP, InboundTransport.WS, InboundTransport.GRPC, InboundTransport.XHTTP)
        InboundProtocol.SHADOWSOCKS -> listOf(InboundTransport.TCP)
    }

    fun security(protocol: InboundProtocol): InboundSecurity = when (protocol) {
        InboundProtocol.VLESS, InboundProtocol.TROJAN -> InboundSecurity.REALITY
        InboundProtocol.VMESS, InboundProtocol.SHADOWSOCKS -> InboundSecurity.NONE
    }

    /** XTLS Vision needs VLESS over raw TCP with REALITY. */
    fun supportsVision(protocol: InboundProtocol, transport: InboundTransport): Boolean =
        protocol == InboundProtocol.VLESS && transport == InboundTransport.TCP

    /** Returns the first invalid field, or null. The SSH port is reserved so the deploy can't lock the user out. */
    fun validate(config: InboundConfig, sshPort: Int): ValidationField? = when {
        config.transport !in transports(config.protocol) -> ValidationField.TRANSPORT
        config.port !in 1..65535 || config.port == sshPort -> ValidationField.INBOUND_PORT
        security(config.protocol) == InboundSecurity.REALITY &&
            (config.sni.isNullOrBlank() || !HostValidator.isHostname(config.sni)) -> ValidationField.SNI
        else -> null
    }
}
