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
    /** apt/dnf upgrade failed; [detail] is "lock" when the package manager stayed busy. */
    data class SystemUpdateFailed(val detail: String?) : AppError
    /** uninstall.sh could not stop or delete 3x-ui. */
    data object UninstallFailed : AppError
    /** Restore found no 3x-ui on the server. */
    data object RestoreNoPanel : AppError
    /** Restore found a panel without a valid public certificate, which the app can't reach safely. */
    data object RestoreNoTrustedCert : AppError
    /** The panel's own updater failed or did not report back; [detail] is the panel's message or the exit code. */
    data class PanelUpdateFailed(val detail: String?) : AppError
    /** Installing a new Xray-core failed or Xray did not come back on the new version; [detail] is the panel's message. */
    data class XrayUpdateFailed(val detail: String?) : AppError
    /** GitHub did not answer the app's release check as expected; [detail] is the HTTP status or GitHub's message. */
    data class ReleaseCheckFailed(val detail: String?) : AppError
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
