package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.inbound.InboundFactory
import dev.autorasqr.xuimanager.data.inbound.InboundRequest
import dev.autorasqr.xuimanager.data.inbound.SelfStealTarget
import dev.autorasqr.xuimanager.data.panel.InboundJson
import dev.autorasqr.xuimanager.data.panel.PanelApi
import dev.autorasqr.xuimanager.data.panel.PanelApiFactory
import dev.autorasqr.xuimanager.data.panel.PanelCredentials
import dev.autorasqr.xuimanager.data.panel.PanelEndpoint
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.data.panel.SubscriptionUpdate
import dev.autorasqr.xuimanager.data.panel.X25519Keys
import dev.autorasqr.xuimanager.data.routing.RoscomVpn
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.ClientRouting
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployWarning
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import dev.autorasqr.xuimanager.domain.model.PanelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

class ProvisionOutcome(val panel: PanelInfo, val warnings: Set<DeployWarning>)

/** Second deploy phase: talks to the panel over pinned HTTPS (API token on v3, login session on v2). */
@Singleton
class PanelProvisioner @Inject constructor(
    private val apiFactory: PanelApiFactory,
    private val random: RandomTokens,
    private val factory: InboundFactory,
) {
    suspend fun provision(
        host: String,
        webBasePath: String,
        params: StoredDeployParams,
        result: DeployResult,
        credentials: PanelCredentials,
        serverKeys: X25519Keys?,
        log: (String) -> Unit,
    ): ProvisionOutcome {
        val warnings = mutableSetOf<DeployWarning>()
        val version = PanelVersion.normalize(result.panelVersion)
        val endpoint = PanelEndpoint(host, result.panelPort, webBasePath)
        val api = apiFactory.create(version, endpoint, credentials)

        log("Connecting to the panel API on port ${result.panelPort} (Let's Encrypt certificate)")
        waitUntilReady(api)

        if (params.adopt) return adopt(api, host, version, webBasePath, params, result, log)

        if (result.existed && params.existingAction == ExistingInstallAction.UPDATE) {
            log("Keeping the existing subscription settings")
            api.updateSubscriptionSettings(SubscriptionUpdate(enabled = true, port = null, path = null, certFile = null, keyFile = null))
        } else {
            log("Configuring the subscription server on port ${result.subPort}")
            api.updateSubscriptionSettings(
                SubscriptionUpdate(
                    enabled = true,
                    port = result.subPort,
                    path = "/" + random.lowerAlphanumeric(16) + "/",
                    certFile = result.subCertFile.orEmpty(),
                    keyFile = result.subKeyFile.orEmpty(),
                ),
            )
        }
        if (params.clientRouting && api.capabilities.routing) {
            log("Subscription routing: RoscomVPN profile for Happ and INCY (RU/BY direct)")
            api.patchSettings(RoscomVpn.subscriptionSettings(ClientRouting.ROSCOM_DEFAULT))
        }
        log("Restarting the panel to apply subscription settings")
        api.restartPanel()
        delay(4_000)
        waitUntilReady(api)

        val subscription = api.subscriptionSettings()
        if (!subscription.tls) warnings += DeployWarning.SUBSCRIPTION_PLAIN_HTTP

        val request = inboundRequest(params, result, host)
        val existing = api.listInbounds().firstOrNull { it.remark == request.remark }
        val inboundId = if (existing != null) {
            log("Reusing inbound #${existing.id} on port ${existing.port}")
            existing.id
        } else {
            val created = factory.create(api, request, serverKeys, log = log)
            if (!created.targetVerified) warnings += DeployWarning.SNI_UNVERIFIED
            created.id
        }
        api.restartXray()
        log("Xray restarted")

        return ProvisionOutcome(
            panel = PanelInfo(
                version = version,
                port = result.panelPort,
                webBasePath = webBasePath,
                username = params.panelUsername,
                subScheme = subscription.scheme,
                subPort = subscription.port,
                subPath = subscription.path,
                defaultInboundId = inboundId,
                deployedAt = System.currentTimeMillis(),
                host = host,
                selfstealPort = result.selfstealPort,
            ),
            warnings = warnings,
        )
    }

    /** Leaves inbounds and subscription paths alone; only switches subscriptions on if they were off. */
    private suspend fun adopt(
        api: PanelApi,
        host: String,
        version: String,
        webBasePath: String,
        params: StoredDeployParams,
        result: DeployResult,
        log: (String) -> Unit,
    ): ProvisionOutcome {
        val warnings = mutableSetOf<DeployWarning>()
        var subscription = api.subscriptionSettings()
        if (!subscription.enabled) {
            log("Subscriptions were off; turning them on so the app can share links")
            api.updateSubscriptionSettings(SubscriptionUpdate(enabled = true, port = null, path = null, certFile = null, keyFile = null))
            api.restartPanel()
            delay(4_000)
            waitUntilReady(api)
            subscription = api.subscriptionSettings()
        }
        if (!subscription.tls) warnings += DeployWarning.SUBSCRIPTION_PLAIN_HTTP
        val inbounds = api.listInbounds()
        log("Found ${inbounds.size} inbound(s); none were changed")
        return ProvisionOutcome(
            panel = PanelInfo(
                version = version,
                port = result.panelPort,
                webBasePath = webBasePath,
                username = params.panelUsername,
                subScheme = subscription.scheme,
                subPort = subscription.port,
                subPath = subscription.path,
                defaultInboundId = inbounds.firstOrNull { it.protocol in CLIENT_PROTOCOLS }?.id,
                deployedAt = System.currentTimeMillis(),
                host = host,
            ),
            warnings = warnings,
        )
    }

    private fun inboundRequest(params: StoredDeployParams, result: DeployResult, host: String): InboundRequest {
        val manual = params.manual
        return if (params.mode == DeployMode.MANUAL && manual != null) {
            InboundRequest(
                remark = "xui-manager-${InboundJson.protocolName(manual.protocol)}-${InboundJson.networkName(manual.transport)}-${result.inboundPort}",
                protocol = manual.protocol,
                transport = manual.transport,
                port = result.inboundPort,
                sni = manual.sni,
                fingerprint = params.fingerprint,
            )
        } else {
            InboundRequest(
                remark = if (result.selfstealPort != null) DeployConfig.SELFSTEAL_REMARK else DeployConfig.INBOUND_REMARK,
                protocol = InboundProtocol.VLESS,
                transport = InboundTransport.TCP,
                port = result.inboundPort,
                sni = params.sni,
                fingerprint = params.fingerprint,
                selfSteal = result.selfstealPort?.let { SelfStealTarget(host, it) },
            )
        }
    }

    private suspend fun waitUntilReady(api: PanelApi) {
        val deadline = System.currentTimeMillis() + 90_000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                api.status()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (e.toAppError()) {
                    AppError.PanelCertificateInvalid, AppError.PanelUnauthorized -> throw e
                    else -> lastError = e
                }
            }
            delay(3_000)
        }
        throw lastError ?: AppException(AppError.PanelStartFailed)
    }

    private companion object {
        val CLIENT_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")
    }
}
