package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.data.panel.X25519Keys
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.CertificateIssue
import dev.autorasqr.xuimanager.domain.model.PrecheckResult
import dev.autorasqr.xuimanager.domain.model.SelfStealIssue
import dev.autorasqr.xuimanager.domain.model.SudoMode
import dev.autorasqr.xuimanager.domain.usecase.HostValidator

internal fun parseKeyValues(text: String): Map<String, String> =
    text.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { '=' in it && !it.startsWith('#') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

object PrecheckParser {
    fun parse(text: String): PrecheckResult {
        val values = parseKeyValues(text)
        val uid = values["uid"]?.toIntOrNull()
            ?: throw AppException(AppError.Unexpected("precheck: no uid"))
        return PrecheckResult(
            uid = uid,
            gid = values["gid"]?.toIntOrNull() ?: uid,
            home = values["home"].orEmpty().ifEmpty { if (uid == 0) "/root" else "/tmp" },
            sudo = when (values["sudo"]) {
                "root" -> SudoMode.ROOT
                "nopasswd" -> SudoMode.NOPASSWD
                "password" -> SudoMode.PASSWORD
                else -> if (uid == 0) SudoMode.ROOT else SudoMode.NONE
            },
            os = values["os"].orEmpty(),
            osVersion = values["os_version"].orEmpty(),
            arch = values["arch"].orEmpty(),
            systemd = values["systemd"] == "1",
            existingInstalled = values["xui_installed"] == "1",
            existingVersion = values["xui_version"]?.takeIf { it.isNotBlank() },
            domainIps = values["domain_ips"]?.let(::ipList),
            serverIps = values["server_ips"]?.let(::ipList).orEmpty(),
        )
    }
}

private fun ipList(value: String): List<String> =
    value.split(' ', '\t').map { it.trim() }.filter { HostValidator.isIpv4(it) }.distinct()

object ResultEnvParser {
    fun parse(text: String): DeployResult {
        val values = parseKeyValues(text)
        fun required(key: String) = values[key]?.takeIf { it.isNotEmpty() }
            ?: throw AppException(AppError.Unexpected("result.env: missing $key"))
        return DeployResult(
            panelPort = required("PANEL_PORT").toInt(),
            apiToken = values["API_TOKEN"]?.takeIf { it.isNotEmpty() },
            webBasePath = values["WEB_BASE_PATH"]?.takeIf { it.isNotEmpty() },
            subPort = values["SUB_PORT"]?.toIntOrNull() ?: 0,
            subCertFile = values["SUB_CERT"]?.takeIf { it.isNotEmpty() },
            subKeyFile = values["SUB_KEY"]?.takeIf { it.isNotEmpty() },
            inboundPort = values["INBOUND_PORT"]?.toIntOrNull() ?: 0,
            panelVersion = values["PANEL_VERSION"].orEmpty().ifEmpty { DeployConfig.XUI_VERSION },
            existed = values["EXISTED"] == "1",
            selfstealPort = values["SELFSTEAL_PORT"]?.toIntOrNull(),
        )
    }
}

/**
 * Parses `xray x25519`. Older Xray prints "Private key:"/"Public key:", newer releases print
 * "PrivateKey:"/"Password:" (the public key) or "Password (PublicKey):", plus a "Hash32:" line.
 */
object X25519Parser {
    private val keyFormat = Regex("^[A-Za-z0-9_-]{43}=?$")

    fun parse(text: String): X25519Keys? {
        var privateKey: String? = null
        var publicKey: String? = null
        text.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val label = line.substring(0, separator).lowercase().filter(Char::isLetter)
            val value = line.substring(separator + 1).trim()
            when {
                !keyFormat.matches(value) -> Unit
                label.startsWith("private") -> privateKey = value
                label.startsWith("public") || label.startsWith("password") -> publicKey = value
            }
        }
        val private = privateKey ?: return null
        val public = publicKey ?: return null
        return X25519Keys(private, public)
    }
}

enum class Liveness { ALIVE, DEAD, NOT_STARTED }

class PollSnapshot(
    val noJob: Boolean,
    val stage: String?,
    val exitCode: String?,
    val liveness: Liveness,
    val logChunk: ByteArray,
)

object PollParser {
    fun parse(output: ByteArray): PollSnapshot {
        val header = ArrayList<String>(3)
        var position = 0
        while (header.size < 3 && position <= output.size) {
            val end = output.indexOf('\n'.code.toByte(), position).let { if (it < 0) output.size else it }
            header += output.decodeToString(position, end).trim()
            position = end + 1
        }
        while (header.size < 3) header += ""
        val chunk = if (position < output.size) output.copyOfRange(position, output.size) else ByteArray(0)
        val noJob = header[0] == "NOJOB"
        return PollSnapshot(
            noJob = noJob,
            stage = header[0].takeIf { it.isNotEmpty() && !noJob },
            exitCode = header[1].takeIf { it.isNotEmpty() },
            liveness = when (header[2]) {
                "1" -> Liveness.ALIVE
                "0" -> Liveness.DEAD
                else -> Liveness.NOT_STARTED
            },
            logChunk = chunk,
        )
    }

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }
}

/** Splits a byte stream into complete UTF-8 lines; a trailing partial line waits for the next chunk. */
class LogLineAssembler {
    private var pending = ByteArray(0)

    fun feed(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        val data = pending + chunk
        val lines = mutableListOf<String>()
        var start = 0
        for (i in data.indices) {
            if (data[i] == '\n'.code.toByte()) {
                lines += data.decodeToString(start, i).trimEnd('\r')
                start = i + 1
            }
        }
        pending = data.copyOfRange(start, data.size)
        return lines
    }

    fun flush(): String? = pending.takeIf { it.isNotEmpty() }?.decodeToString()?.also { pending = ByteArray(0) }
}

object DeployExitCodes {
    fun toAppError(code: String): AppError {
        val name = code.substringBefore(':')
        val detail = code.substringAfter(':', "").ifEmpty { null }
        return when (name) {
            "E_NOT_ROOT" -> AppError.NoRootAccess
            "E_UNSUPPORTED" -> AppError.UnsupportedSystem
            "E_DEPS" -> AppError.InstallFailed("dependencies")
            "E_ALREADY_INSTALLED" -> AppError.AlreadyInstalled(null)
            "E_CLEAN_POSTGRES" -> AppError.CleanReinstallPostgres
            "E_PORT_BUSY" -> AppError.PortBusy(detail?.toIntOrNull() ?: 0)
            "E_DOWNLOAD" -> AppError.InstallerDownloadFailed
            "E_INSTALLER_HASH" -> AppError.InstallerHashMismatch
            "E_INSTALL" -> AppError.InstallFailed(detail)
            "E_CERT" -> AppError.InstallFailed("certificate")
            "E_TLS" -> AppError.CertificateIssueFailed(CertificateIssue.VALIDATION_FAILED)
            "E_TLS_PORT80" -> AppError.CertificateIssueFailed(CertificateIssue.PORT_80_BUSY)
            "E_TLS_UNAVAILABLE" -> AppError.CertificateIssueFailed(CertificateIssue.NO_PUBLIC_ADDRESS)
            "E_PANEL_START" -> AppError.PanelStartFailed
            "E_TOKEN" -> AppError.ApiTokenFailed
            "E_INTERRUPTED" -> AppError.DeployInterrupted
            "E_CADDY_DOWNLOAD" -> AppError.SelfStealFailed(SelfStealIssue.DOWNLOAD)
            "E_CADDY_HASH" -> AppError.SelfStealFailed(SelfStealIssue.CHECKSUM)
            "E_SELFSTEAL" -> AppError.SelfStealFailed(SelfStealIssue.SETUP)
            "E_GEODATA" -> AppError.GeodataFailed
            "E_SYSUPDATE" -> AppError.SystemUpdateFailed(detail)
            "E_UNINSTALL" -> AppError.UninstallFailed
            "E_RESTORE_MISSING" -> AppError.RestoreNoPanel
            "E_RESTORE_CERT" -> AppError.RestoreNoTrustedCert
            "E_REBOOT" -> AppError.RebootFailed(detail)
            else -> AppError.Unexpected(code)
        }
    }
}
