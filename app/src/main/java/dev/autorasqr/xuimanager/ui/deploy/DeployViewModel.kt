package dev.autorasqr.xuimanager.ui.deploy

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.data.deploy.SniPresets
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.DEFAULT_FINGERPRINT
import dev.autorasqr.xuimanager.domain.model.DeployMode
import dev.autorasqr.xuimanager.domain.model.DeployOptions
import dev.autorasqr.xuimanager.domain.model.DeployProgress
import dev.autorasqr.xuimanager.domain.model.ExistingInstallAction
import dev.autorasqr.xuimanager.domain.model.InboundConfig
import dev.autorasqr.xuimanager.domain.model.InboundProtocol
import dev.autorasqr.xuimanager.domain.model.InboundSecurity
import dev.autorasqr.xuimanager.domain.model.InboundTransport
import dev.autorasqr.xuimanager.domain.model.PrecheckResult
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.DeployRepository
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.usecase.InboundMatrix
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.DeployRoute
import dev.autorasqr.xuimanager.ui.servers.HostKeyPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeployUiState(
    val server: Server? = null,
    val mode: DeployMode = DeployMode.ONE_CLICK,
    /** One-click Reality target; null = automatic choice. */
    val sni: String? = null,
    /** uTLS fingerprint for the Reality inbound, both modes. */
    val fingerprint: String = DEFAULT_FINGERPRINT,
    /** One-click on a server with a domain: borrow the server's own decoy site instead of someone else's. */
    val selfSteal: Boolean = true,
    /** RoscomVPN routing profile in the subscription (RU/BY direct in Happ and INCY). */
    val clientRouting: Boolean = true,
    val manual: ManualForm = ManualForm(),
    val prechecking: Boolean = false,
    val precheck: PrecheckResult? = null,
    val askExistingAction: Boolean = false,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val error: AppError? = null,
    val detachedJobId: Long? = null,
    val jobId: Long? = null,
    val progress: DeployProgress? = null,
)

/** Manual-mode inputs as typed; validated with InboundMatrix when the deploy starts. */
data class ManualForm(
    val protocol: InboundProtocol = InboundProtocol.VLESS,
    val transport: InboundTransport = InboundTransport.TCP,
    val port: String = "443",
    val sni: String = SniPresets.targets.first(),
    val fieldError: ValidationField? = null,
) {
    val transports: List<InboundTransport> get() = InboundMatrix.transports(protocol)
    val security: InboundSecurity get() = InboundMatrix.security(protocol)

    fun toConfig(): InboundConfig? = port.toIntOrNull()?.let { InboundConfig(protocol, transport, it, sni.trim().takeIf { s -> s.isNotEmpty() }) }
}

@HiltViewModel
class DeployViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val deployRepository: DeployRepository,
) : ViewModel() {

    val serverId: Long = savedStateHandle.toRoute<DeployRoute>().serverId
    private val _state = MutableStateFlow(DeployUiState())
    val state: StateFlow<DeployUiState> = _state.asStateFlow()
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(server = serverRepository.getServer(serverId)) }
            val unfinished = deployRepository.unfinishedJob(serverId) ?: return@launch
            if (deployRepository.isAttached(unfinished)) observe(unfinished) else _state.update { it.copy(detachedJobId = unfinished) }
        }
    }

    fun onSni(value: String?) = _state.update { it.copy(sni = value) }
    fun onFingerprint(value: String) = _state.update { it.copy(fingerprint = value) }
    fun onSelfSteal(value: Boolean) = _state.update { it.copy(selfSteal = value) }
    fun onClientRouting(value: Boolean) = _state.update { it.copy(clientRouting = value) }
    fun onMode(value: DeployMode) = _state.update { it.copy(mode = value, error = null) }

    fun onProtocol(value: InboundProtocol) = editManual {
        val transports = InboundMatrix.transports(value)
        copy(protocol = value, transport = transport.takeIf { it in transports } ?: transports.first(), fieldError = null)
    }

    fun onTransport(value: InboundTransport) = editManual { copy(transport = value, fieldError = null) }
    fun onPort(value: String) = editManual { copy(port = value.filter(Char::isDigit).take(5), fieldError = null) }
    fun onManualSni(value: String) = editManual { copy(sni = value.trim().lowercase(), fieldError = null) }

    fun start() {
        val current = _state.value
        if (current.prechecking) return
        if (current.mode == DeployMode.MANUAL) {
            val config = current.manual.toConfig()
            val invalid = if (config == null) ValidationField.INBOUND_PORT else InboundMatrix.validate(config, current.server?.sshPort ?: 22)
            if (invalid != null) {
                editManual { copy(fieldError = invalid) }
                return
            }
        }
        _state.update { it.copy(prechecking = true, error = null) }
        viewModelScope.launch {
            try {
                val precheck = deployRepository.precheck(serverId)
                _state.update { it.copy(precheck = precheck) }
                if (precheck.existingInstalled) {
                    _state.update { it.copy(prechecking = false, askExistingAction = true) }
                } else {
                    launchDeploy(precheck, null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handle(e.asAppError())
            }
        }
    }

    fun chooseExistingAction(action: ExistingInstallAction?) {
        val precheck = _state.value.precheck
        _state.update { it.copy(askExistingAction = false) }
        if (action == null || precheck == null) return
        _state.update { it.copy(prechecking = true) }
        viewModelScope.launch {
            try {
                launchDeploy(precheck, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handle(e.asAppError())
            }
        }
    }

    fun trustHostKey() {
        val prompt = _state.value.hostKeyPrompt ?: return
        _state.update { it.copy(hostKeyPrompt = null) }
        viewModelScope.launch {
            serverRepository.pinHostKey(serverId, prompt.presented)
            _state.update { it.copy(server = serverRepository.getServer(serverId)) }
            start()
        }
    }

    fun dismissHostKey() = _state.update { it.copy(hostKeyPrompt = null) }

    fun resume() {
        val jobId = _state.value.detachedJobId ?: return
        viewModelScope.launch {
            deployRepository.resume(jobId)
            _state.update { it.copy(detachedJobId = null) }
            observe(jobId)
        }
    }

    /** Back to the setup form after a failed run. */
    fun reset() {
        progressJob?.cancel()
        _state.update { it.copy(jobId = null, progress = null, error = null, precheck = null) }
    }

    private suspend fun launchDeploy(precheck: PrecheckResult, action: ExistingInstallAction?) {
        val current = _state.value
        val options = DeployOptions(
            // Adopting keeps the panel as it is, so no inbound is requested whatever the form says.
            mode = if (action == ExistingInstallAction.ADOPT) DeployMode.ONE_CLICK else current.mode,
            sni = current.sni,
            existingAction = action,
            manual = current.manual.toConfig().takeIf { current.mode == DeployMode.MANUAL && action != ExistingInstallAction.ADOPT },
            fingerprint = current.fingerprint,
            selfSteal = current.selfSteal && current.server?.domain != null,
            clientRouting = current.clientRouting && current.server?.isNode != true,
        )
        val jobId = deployRepository.startDeploy(serverId, options, precheck)
        _state.update { it.copy(prechecking = false) }
        observe(jobId)
    }

    private fun observe(jobId: Long) {
        progressJob?.cancel()
        _state.update { it.copy(jobId = jobId) }
        progressJob = viewModelScope.launch {
            deployRepository.observeProgress(jobId).collect { progress -> _state.update { it.copy(progress = progress) } }
        }
    }

    private fun editManual(transform: ManualForm.() -> ManualForm) = _state.update { it.copy(manual = it.manual.transform()) }

    private fun handle(error: AppError) {
        val server = _state.value.server
        val prompt = when (error) {
            is AppError.HostKeyUnknown -> server?.let { HostKeyPrompt(it.host, error.presented, null) }
            is AppError.HostKeyMismatch -> server?.let { HostKeyPrompt(it.host, error.presented, error.expected) }
            else -> null
        }
        if (error is AppError.Validation) {
            _state.update { it.copy(prechecking = false, manual = it.manual.copy(fieldError = error.field)) }
            return
        }
        _state.update {
            it.copy(prechecking = false, hostKeyPrompt = prompt, error = if (prompt == null) error else null)
        }
    }
}
