package dev.autorasqr.xuimanager.domain.usecase

import dev.autorasqr.xuimanager.domain.model.ClientDraft
import dev.autorasqr.xuimanager.domain.model.ServerDraft
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.ValidationField

object HostValidator {
    private val ipv4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val hostname = Regex("""^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$""")
    private val ipv6 = Regex("""^[0-9A-Fa-f:.]+$""")

    fun isIpv4(host: String) = ipv4.matches(host)
    fun isIpv6(host: String) = host.contains(':') && ipv6.matches(host)
    fun isHostname(host: String) = !isIpv4(host) && hostname.matches(host) && host.any { it.isLetter() }
    fun isValid(host: String) = isIpv4(host) || isIpv6(host) || isHostname(host)
}

object DomainCheck {
    /**
     * Let's Encrypt validates the domain over port 80 of whatever its A record points at, so at least one record
     * must be one of this server's addresses. [sshHost] counts too when it is an IPv4 address.
     */
    fun pointsHere(resolved: List<String>, serverIps: List<String>, sshHost: String): Boolean {
        val own = serverIps.toSet() + listOfNotNull(sshHost.takeIf(HostValidator::isIpv4))
        return resolved.any { it in own }
    }
}

object ServerDraftValidator {
    private val user = Regex("""^[a-z_][a-z0-9_.-]{0,31}\$?$""", RegexOption.IGNORE_CASE)

    /** Returns the first invalid field, or null. [hasStoredSecret] is true when editing and a secret is already saved. */
    fun validate(draft: ServerDraft, hasStoredSecret: Boolean): ValidationField? = when {
        draft.name.isBlank() || draft.name.length > 64 -> ValidationField.NAME
        !HostValidator.isValid(draft.host) -> ValidationField.HOST
        draft.domain.isNotBlank() && !HostValidator.isHostname(draft.domain.trim()) -> ValidationField.DOMAIN
        draft.sshPort !in 1..65535 -> ValidationField.PORT
        !user.matches(draft.sshUser) -> ValidationField.USER
        draft.authType == SshAuthType.PASSWORD && draft.password.isEmpty() && !hasStoredSecret -> ValidationField.PASSWORD
        draft.authType == SshAuthType.PRIVATE_KEY && draft.privateKey.isBlank() && !hasStoredSecret -> ValidationField.PRIVATE_KEY
        draft.authType == SshAuthType.PRIVATE_KEY && draft.privateKey.isNotBlank() &&
            !draft.privateKey.contains("PRIVATE KEY") && !draft.privateKey.contains("PuTTY-User-Key-File") -> ValidationField.PRIVATE_KEY
        else -> null
    }
}

object ClientDraftValidator {
    private val email = Regex("""^[A-Za-z0-9._@+-]{1,64}$""")
    private val subId = Regex("""^[A-Za-z0-9_-]{4,64}$""")

    /**
     * [existingEmails] are the other clients' emails (exclude the client being edited). A negative expiryTime is
     * allowed: it is 3x-ui's "delayed start" and only comes from clients created in the panel itself.
     */
    fun validate(draft: ClientDraft, existingEmails: Collection<String>): ValidationField? = when {
        !email.matches(draft.email) -> ValidationField.EMAIL
        existingEmails.any { it.equals(draft.email, ignoreCase = true) } -> ValidationField.EMAIL_TAKEN
        !subId.matches(draft.subId) -> ValidationField.SUB_ID
        draft.totalBytes < 0 -> ValidationField.TRAFFIC
        draft.inboundIds.isEmpty() -> ValidationField.INBOUNDS
        else -> null
    }
}
