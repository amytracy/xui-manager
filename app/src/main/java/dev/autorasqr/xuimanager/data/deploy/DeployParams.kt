package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.DEFAULT_FINGERPRINT
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.InboundConfig
import dev.autorasqr.xuimanager.domain.model.SudoMode
import kotlinx.serialization.Serializable

/** Everything a job needs to (re)attach, stored in deploy_jobs.paramsJson. Secrets go to SecretStore instead. */
@Serializable
data class StoredDeployParams(
    val mode: DeployMode,
    val sni: String?,
    val existingAction: ExistingInstallAction?,
    val existingInstalled: Boolean,
    val sudo: SudoMode,
    val uid: Int,
    val gid: Int,
    val home: String,
    val sslMode: String,
    val panelUsername: String,
    val panelPort: Int,
    val subPort: Int,
    val inboundPort: Int,
    val inboundPortStrict: Boolean,
    /** Manual mode only. */
    val manual: InboundConfig? = null,
    /** Name the panel certificate is issued for (domain or IP); null in jobs created before it was recorded. */
    val serverHost: String? = null,
    val fingerprint: String = DEFAULT_FINGERPRINT,
    /** One-click with a domain: Reality borrows the server's own decoy site (Caddy on a local port). */
    val selfSteal: Boolean = false,
    /** Hand the RoscomVPN routing profile to clients through the subscription. */
    val clientRouting: Boolean = false,
) {
    val adopt: Boolean get() = existingAction == ExistingInstallAction.ADOPT
}

/** Values run.sh reports in result.env. */
data class DeployResult(
    val panelPort: Int,
    /** Missing on 3x-ui 2.x, which has no API tokens. */
    val apiToken: String?,
    /** Reported only when an existing panel was adopted; otherwise the app chose it. */
    val webBasePath: String?,
    val subPort: Int,
    val subCertFile: String?,
    val subKeyFile: String?,
    /** 0 when no inbound was requested (adopt). */
    val inboundPort: Int,
    val panelVersion: String,
    val existed: Boolean,
    /** Local port of the self-steal site, when it was set up. */
    val selfstealPort: Int? = null,
) {
    override fun toString() = "DeployResult(panelPort=$panelPort, subPort=$subPort, inboundPort=$inboundPort, version=$panelVersion)"
}
