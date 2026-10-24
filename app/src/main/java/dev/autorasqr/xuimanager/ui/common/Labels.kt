package dev.autorasqr.xuimanager.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.CertificateIssue
import dev.autorasqr.xuimanager.domain.model.DeployStage
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundSecurity
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import dev.autorasqr.xuimanager.domain.model.SelfStealIssue
import dev.autorasqr.xuimanager.domain.model.ValidationField

@StringRes
fun DeployStage.labelRes(): Int = when (this) {
    DeployStage.CONNECT -> R.string.stage_connect
    DeployStage.UPLOAD -> R.string.stage_upload
    DeployStage.PREPARE -> R.string.stage_prepare
    DeployStage.EXISTING -> R.string.stage_existing
    DeployStage.PORTS -> R.string.stage_ports
    DeployStage.FIREWALL -> R.string.stage_firewall
    DeployStage.DOWNLOAD -> R.string.stage_download
    DeployStage.INSTALL -> R.string.stage_install
    DeployStage.CONFIGURE -> R.string.stage_configure
    DeployStage.SELFSTEAL -> R.string.stage_selfsteal
    DeployStage.KEYS -> R.string.stage_keys
    DeployStage.RESTART -> R.string.stage_restart
    DeployStage.PANEL_SETUP -> R.string.stage_panel_setup
    DeployStage.DONE -> R.string.stage_done
}

@StringRes
fun ValidationField.messageRes(): Int = when (this) {
    ValidationField.NAME -> R.string.error_validation_name
    ValidationField.HOST -> R.string.error_validation_host
    ValidationField.PORT -> R.string.error_validation_port
    ValidationField.USER -> R.string.error_validation_user
    ValidationField.PASSWORD -> R.string.error_validation_password
    ValidationField.PRIVATE_KEY -> R.string.error_validation_key
    ValidationField.EMAIL -> R.string.error_validation_email
    ValidationField.EMAIL_TAKEN -> R.string.error_validation_email_taken
    ValidationField.SUB_ID -> R.string.error_validation_sub_id
    ValidationField.TRAFFIC -> R.string.error_validation_traffic
    ValidationField.EXPIRY -> R.string.error_validation_expiry
    ValidationField.INBOUNDS -> R.string.error_validation_inbounds
    ValidationField.INBOUND_PORT -> R.string.error_validation_inbound_port
    ValidationField.SNI -> R.string.error_validation_sni
    ValidationField.TRANSPORT -> R.string.error_validation_transport
    ValidationField.DOMAIN -> R.string.error_validation_domain
}

fun InboundProtocol.label(): String = when (this) {
    InboundProtocol.VLESS -> "VLESS"
    InboundProtocol.TROJAN -> "Trojan"
    InboundProtocol.VMESS -> "VMess"
    InboundProtocol.SHADOWSOCKS -> "Shadowsocks 2022"
}

fun InboundTransport.label(): String = when (this) {
    InboundTransport.TCP -> "TCP"
    InboundTransport.XHTTP -> "XHTTP"
    InboundTransport.GRPC -> "gRPC"
    InboundTransport.WS -> "WebSocket"
}

@StringRes
fun InboundSecurity.labelRes(): Int = when (this) {
    InboundSecurity.REALITY -> R.string.security_reality
    InboundSecurity.NONE -> R.string.security_protocol
}

/** "#3 xui-manager-reality · vless/tcp/reality :443" */
fun InboundSummary.label(): String =
    "#$id ${remark.ifBlank { protocol }} · $protocol/$network" + (if (security != "none") "/$security" else "") + " :$port"

fun AppError.message(context: Context): String = when (this) {
    AppError.Timeout -> context.getString(R.string.error_timeout)
    is AppError.UnknownHost -> context.getString(R.string.error_unknown_host, host)
    AppError.ConnectionRefused -> context.getString(R.string.error_connection_refused)
    AppError.NetworkUnreachable -> context.getString(R.string.error_network_unreachable)
    AppError.SshAuthFailed -> context.getString(R.string.error_ssh_auth)
    is AppError.HostKeyUnknown -> context.getString(R.string.error_host_key_unknown)
    is AppError.HostKeyMismatch -> context.getString(R.string.error_host_key_mismatch)
    AppError.InvalidPrivateKey -> context.getString(R.string.error_invalid_key)
    AppError.SudoPasswordRequired -> context.getString(R.string.error_sudo_password)
    AppError.NoRootAccess -> context.getString(R.string.error_no_root)
    is AppError.AlreadyInstalled -> context.getString(R.string.error_already_installed)
    is AppError.PortBusy -> context.getString(R.string.error_port_busy, port)
    AppError.UnsupportedSystem -> context.getString(R.string.error_unsupported_system)
    AppError.InstallerDownloadFailed -> context.getString(R.string.error_installer_download)
    AppError.InstallerHashMismatch -> context.getString(R.string.error_installer_hash)
    is AppError.InstallFailed -> detail?.let { context.getString(R.string.error_install_failed, it) }
        ?: context.getString(R.string.error_install_failed_generic)
    AppError.PanelStartFailed -> context.getString(R.string.error_panel_start)
    AppError.ApiTokenFailed -> context.getString(R.string.error_api_token)
    AppError.CleanReinstallPostgres -> context.getString(R.string.error_clean_postgres)
    AppError.DeployInterrupted -> context.getString(R.string.error_deploy_interrupted)
    AppError.DeployAlreadyRunning -> context.getString(R.string.error_deploy_running)
    is AppError.DomainDnsMismatch -> if (resolved.isEmpty()) {
        context.getString(R.string.error_domain_unresolved, domain, serverIps.joinToString().ifEmpty { "?" })
    } else {
        context.getString(R.string.error_domain_mismatch, domain, resolved.joinToString(), serverIps.joinToString().ifEmpty { "?" })
    }
    AppError.PanelCertificateInvalid -> context.getString(R.string.error_cert_invalid)
    is AppError.CertificateIssueFailed -> context.getString(
        when (reason) {
            CertificateIssue.PORT_80_BUSY -> R.string.error_cert_port80
            CertificateIssue.NO_PUBLIC_ADDRESS -> R.string.error_cert_no_address
            CertificateIssue.VALIDATION_FAILED -> R.string.error_cert_issue
        },
    )
    is AppError.SelfStealFailed -> context.getString(
        when (reason) {
            SelfStealIssue.DOWNLOAD -> R.string.error_selfsteal_download
            SelfStealIssue.CHECKSUM -> R.string.error_selfsteal_checksum
            SelfStealIssue.SETUP -> R.string.error_selfsteal_setup
            SelfStealIssue.NO_DOMAIN -> R.string.error_selfsteal_no_domain
        },
    )
    AppError.GeodataFailed -> context.getString(R.string.error_geodata)
    is AppError.SystemUpdateFailed -> context.getString(
        when (detail) {
            "lock" -> R.string.error_sysupdate_lock
            "reboot" -> R.string.error_sysupdate_reboot
            else -> R.string.error_sysupdate
        },
    )
    is AppError.PanelUpdateFailed -> context.getString(R.string.error_panel_update, detail ?: "—")
    AppError.PanelUnauthorized -> context.getString(R.string.error_panel_unauthorized)
    AppError.PanelNotFound -> context.getString(R.string.error_panel_not_found)
    AppError.PanelNotDeployed -> context.getString(R.string.error_panel_not_deployed)
    is AppError.UnsupportedPanelVersion -> context.getString(R.string.error_unsupported_panel, version)
    AppError.NoInbound -> context.getString(R.string.error_no_inbound)
    is AppError.PanelError -> context.getString(R.string.error_panel, message)
    is AppError.Validation -> context.getString(field.messageRes())
    is AppError.Unexpected -> context.getString(R.string.error_unexpected, message ?: "—")
}

@Composable
fun AppError.message(): String = message(LocalContext.current)
