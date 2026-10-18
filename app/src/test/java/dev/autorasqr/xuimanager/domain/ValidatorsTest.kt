package dev.autorasqr.xuimanager.domain

import dev.autorasqr.xuimanager.domain.model.ClientDraft
import dev.autorasqr.xuimanager.domain.model.ServerDraft
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.usecase.ClientDraftValidator
import dev.autorasqr.xuimanager.domain.usecase.DomainCheck
import dev.autorasqr.xuimanager.domain.usecase.HostValidator
import dev.autorasqr.xuimanager.domain.usecase.ServerDraftValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    @Test
    fun hosts() {
        assertTrue(HostValidator.isIpv4("203.0.113.7"))
        assertFalse(HostValidator.isIpv4("256.1.1.1"))
        assertTrue(HostValidator.isIpv6("2001:db8::1"))
        assertTrue(HostValidator.isHostname("vps.example.com"))
        assertFalse(HostValidator.isHostname("203.0.113.7"))
        assertFalse(HostValidator.isValid("bad host"))
        assertFalse(HostValidator.isValid("a;rm -rf"))
    }

    private val draft = ServerDraft(null, "VPS", "203.0.113.7", 22, "root", SshAuthType.PASSWORD, "pw", "", "")

    @Test
    fun servers() {
        assertNull(ServerDraftValidator.validate(draft, hasStoredSecret = false))
        assertEquals(ValidationField.PASSWORD, ServerDraftValidator.validate(draft.copy(password = ""), false))
        assertNull(ServerDraftValidator.validate(draft.copy(password = ""), hasStoredSecret = true))
        assertEquals(ValidationField.HOST, ServerDraftValidator.validate(draft.copy(host = "x y"), false))
        assertEquals(ValidationField.PORT, ServerDraftValidator.validate(draft.copy(sshPort = 0), false))
        assertEquals(ValidationField.USER, ServerDraftValidator.validate(draft.copy(sshUser = "bad user"), false))
        val keyDraft = draft.copy(authType = SshAuthType.PRIVATE_KEY, password = "")
        assertEquals(ValidationField.PRIVATE_KEY, ServerDraftValidator.validate(keyDraft.copy(privateKey = "not a key"), false))
        assertNull(ServerDraftValidator.validate(keyDraft.copy(privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n…"), false))
    }

    @Test
    fun domains() {
        assertNull(ServerDraftValidator.validate(draft.copy(domain = "vpn.example.com"), false))
        assertNull(ServerDraftValidator.validate(draft.copy(domain = "  "), false))
        assertEquals(ValidationField.DOMAIN, ServerDraftValidator.validate(draft.copy(domain = "203.0.113.7"), false))
        assertEquals(ValidationField.DOMAIN, ServerDraftValidator.validate(draft.copy(domain = "https://x.com/a"), false))
    }

    @Test
    fun `domain must point at one of the server addresses`() {
        assertTrue(DomainCheck.pointsHere(listOf("203.0.113.7"), emptyList(), "203.0.113.7"))
        assertTrue(DomainCheck.pointsHere(listOf("198.51.100.1", "203.0.113.9"), listOf("10.0.0.5", "203.0.113.9"), "vps.example.com"))
        assertFalse(DomainCheck.pointsHere(listOf("104.21.3.4"), listOf("203.0.113.7"), "203.0.113.7"))
        assertFalse(DomainCheck.pointsHere(emptyList(), listOf("203.0.113.7"), "203.0.113.7"))
    }

    @Test
    fun clients() {
        val client = ClientDraft("phone-anna", 0, 0, "abcd1234efgh5678", inboundIds = listOf(1))
        assertNull(ClientDraftValidator.validate(client, listOf("other")))
        assertEquals(ValidationField.EMAIL_TAKEN, ClientDraftValidator.validate(client, listOf("Phone-Anna")))
        assertEquals(ValidationField.EMAIL, ClientDraftValidator.validate(client.copy(email = "has space"), emptyList()))
        assertEquals(ValidationField.SUB_ID, ClientDraftValidator.validate(client.copy(subId = "a/b"), emptyList()))
        assertEquals(ValidationField.SUB_ID, ClientDraftValidator.validate(client.copy(subId = "abc"), emptyList()))
        assertEquals(ValidationField.INBOUNDS, ClientDraftValidator.validate(client.copy(inboundIds = emptyList()), emptyList()))
        assertNull(ClientDraftValidator.validate(client.copy(expiryTime = -7 * 86_400_000L), emptyList()))
    }
}
