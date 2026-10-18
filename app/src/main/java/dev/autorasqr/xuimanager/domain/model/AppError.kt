package dev.autorasqr.xuimanager.domain.model

/** Every failure the UI knows how to explain. Messages are resolved to localized strings in the UI layer. */
sealed interface AppError {
    data object Timeout : AppError
    data class UnknownHost(val host: String) : AppError
    data object ConnectionRefused : AppError
    data object NetworkUnreachable : AppError
    data object SshAuthFailed : AppError
    data class HostKeyUnknown(val presented: HostKeyPin) : AppError
    data class HostKeyMismatch(val expected: HostKeyPin, val presented: HostKeyPin) : AppError
    data object InvalidPrivateKey : AppError
    data object SudoPasswordRequired : AppError
    data object NoRootAccess : AppError
    data class AlreadyInstalled(val version: String?) : AppError
    data class PortBusy(val port: Int) : AppError
    data object UnsupportedSystem : AppError
    data object InstallerDownloadFailed : AppError
    data object InstallerHashMismatch : AppError
    data class InstallFailed(val detail: String?) : AppError
    data object PanelStartFailed : AppError
    data object ApiTokenFailed : AppError
    data object CleanReinstallPostgres : AppError
    data object DeployInterrupted : AppError
    data object DeployAlreadyRunning : AppError
    /** The domain's A records do not point at this server (or it does not resolve at all). */
    data class DomainDnsMismatch(val domain: String, val resolved: List<String>, val serverIps: List<String>) : AppError
    /** The panel's TLS certificate is not trusted by Android (expired, wrong host, not from a public CA). */
    data object PanelCertificateInvalid : AppError
    data class CertificateIssueFailed(val reason: CertificateIssue) : AppError
    data class SelfStealFailed(val reason: SelfStealIssue) : AppError
    /** The RoscomVPN geo files could not be downloaded or did not match their published checksum. */
    data object GeodataFailed : AppError
    data object PanelUnauthorized : AppError
    data object PanelNotFound : AppError
    data object PanelNotDeployed : AppError
    data class UnsupportedPanelVersion(val version: String) : AppError
    data object NoInbound : AppError
    data class PanelError(val message: String) : AppError
    data class Validation(val field: ValidationField) : AppError
    data class Unexpected(val message: String?) : AppError
}

enum class CertificateIssue { PORT_80_BUSY, NO_PUBLIC_ADDRESS, VALIDATION_FAILED }

enum class SelfStealIssue { DOWNLOAD, CHECKSUM, SETUP, NO_DOMAIN }

enum class ValidationField {
    NAME, HOST, PORT, USER, PASSWORD, PRIVATE_KEY,
    EMAIL, EMAIL_TAKEN, SUB_ID, TRAFFIC, EXPIRY, INBOUNDS,
    INBOUND_PORT, SNI, TRANSPORT, DOMAIN,
}

class AppException(val error: AppError, cause: Throwable? = null) : Exception(error.toString(), cause)
