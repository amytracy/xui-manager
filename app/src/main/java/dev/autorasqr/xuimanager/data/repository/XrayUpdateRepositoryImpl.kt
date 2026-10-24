package dev.autorasqr.xuimanager.data.repository

import dev.autorasqr.xuimanager.data.deploy.DeployConfig
import dev.autorasqr.xuimanager.data.error.toAppError
import dev.autorasqr.xuimanager.data.panel.PanelApiProvider
import dev.autorasqr.xuimanager.data.panel.PanelVersion
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.UpdateCheck
import dev.autorasqr.xuimanager.domain.model.XrayUpdateOffer
import dev.autorasqr.xuimanager.domain.model.XrayUpdateProgress
import dev.autorasqr.xuimanager.domain.repository.XrayUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Xray-core updates through the panel's own installer (installXray): the panel downloads the release from GitHub,
 * swaps the binary and restarts Xray, so proxies are down for the duration of the download.
 */
@Singleton
class XrayUpdateRepositoryImpl @Inject constructor(
    private val panels: PanelApiProvider,
) : XrayUpdateRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progress = ConcurrentHashMap<Long, MutableStateFlow<XrayUpdateProgress?>>()
    private val checks = ConcurrentHashMap<Long, UpdateCheck<XrayUpdateOffer>>()
    private val prompted = ConcurrentHashMap.newKeySet<Long>()

    override suspend fun check(serverId: Long, refresh: Boolean): UpdateCheck<XrayUpdateOffer> = withContext(Dispatchers.IO) {
        if (!refresh) checks[serverId]?.let { return@withContext it }
        val result = try {
            val api = panels.forServer(serverId).second
            if (!api.capabilities.xrayInstall) {
                UpdateCheck.Unavailable()
            } else {
                val current = api.status().xrayVersion
                offerFrom(current, api.xrayVersions())?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Panel down or no GitHub access from the server; not remembered, so the next visit tries again.
            return@withContext UpdateCheck.Unavailable(e.toAppError())
        }
        checks[serverId] = result
        result
    }

    override fun shouldPrompt(serverId: Long): Boolean = serverId !in prompted

    override fun markPrompted(serverId: Long) {
        prompted += serverId
    }

    override fun progress(serverId: Long): StateFlow<XrayUpdateProgress?> = flowFor(serverId)

    override fun clearProgress(serverId: Long) {
        flowFor(serverId).update { if (it?.state == DeployState.RUNNING) it else null }
    }

    override fun start(serverId: Long, offer: XrayUpdateOffer) {
        val flow = flowFor(serverId)
        if (flow.value?.state == DeployState.RUNNING) return
        val target = offer.latestVersion
        flow.value = XrayUpdateProgress(target, DeployState.RUNNING, emptyList(), null)
        val log: (String) -> Unit = { line -> flow.update { it?.copy(log = (it.log + line).takeLast(DeployConfig.MAX_LOG_LINES)) } }
        scope.launch {
            try {
                val api = panels.forServer(serverId).second
                log("Installing Xray $target (now ${offer.currentVersion}); Xray is stopped meanwhile")
                try {
                    api.installXray(target)
                    log("The panel reports the install as done")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A slow download can outlast the HTTP read timeout while the panel keeps installing; anything
                    // else (the panel refused, e.g. an unknown tag) is final.
                    val error = e.toAppError()
                    if (error != AppError.Timeout) {
                        throw AppException(AppError.XrayUpdateFailed((error as? AppError.PanelError)?.message ?: error.toString()), e)
                    }
                    log("No answer yet, waiting for Xray to come back")
                }

                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                while (true) {
                    val status = try {
                        api.status()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (status != null && isDone(status.xrayState, status.xrayVersion, target)) {
                        log("Xray ${PanelVersion.normalize(status.xrayVersion)} is running")
                        break
                    }
                    if (System.currentTimeMillis() > deadline) {
                        val seen = status?.let { "Xray ${it.xrayVersion.ifBlank { "?" }} ${it.xrayState.ifBlank { "?" }}" }
                        throw AppException(AppError.XrayUpdateFailed(seen ?: "the panel does not answer"))
                    }
                    delay(POLL_MS)
                }
                flow.update { it?.copy(state = DeployState.SUCCEEDED) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toAppError()
                log("ERROR: $error")
                flow.update { it?.copy(state = DeployState.FAILED, error = error) }
            } finally {
                checks.remove(serverId)
            }
        }
    }

    private fun flowFor(serverId: Long) = progress.getOrPut(serverId) { MutableStateFlow(null) }

    companion object {
        private const val POLL_MS = 3_000L
        private const val TIMEOUT_MS = 5 * 60_000L

        /**
         * The newest parsable tag in [versions] when it is newer than [current] (the status reports "26.7.1", the
         * release list "v26.7.1"). Null when Xray is current or the list is empty. An unknown current version (Xray
         * not running yet) still gets the offer.
         */
        internal fun offerFrom(current: String, versions: List<String>): XrayUpdateOffer? {
            val latest = versions
                .map(String::trim)
                .filter { PanelVersion.parse(it).isNotEmpty() }
                .maxWithOrNull { a, b -> PanelVersion.compare(a, b) ?: 0 }
                ?: return null
            val cmp = PanelVersion.compare(current, latest)
            if (cmp != null && cmp >= 0) return null
            return XrayUpdateOffer(if (cmp == null) current.trim() else PanelVersion.normalize(current), latest)
        }

        internal fun isDone(state: String, version: String, target: String): Boolean =
            state.equals("running", ignoreCase = true) && (PanelVersion.compare(version, target) ?: -1) >= 0
    }
}
