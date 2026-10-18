package dev.autorasqr.xuimanager.ui.nodes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.NodeDraft
import dev.autorasqr.xuimanager.domain.model.NodeInbound
import dev.autorasqr.xuimanager.domain.model.NodeTestResult
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.NodeRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.NodeEditRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The panel's Add/Edit Node form (NodeFormModal). */
data class NodeEditState(
    val nodeId: Int,
    val loaded: Boolean,
    val name: String = "",
    val remark: String = "",
    val scheme: String = "https",
    val address: String = "",
    val port: String = "2053",
    val basePath: String = "/",
    val apiToken: String = "",
    val hasStoredToken: Boolean = false,
    val enabled: Boolean = true,
    val allowPrivateAddress: Boolean = false,
    val tlsVerifyMode: String = "verify",
    val pinnedCertSha256: String = "",
    val inboundSyncMode: String = "all",
    val inboundTags: List<String> = emptyList(),
    val inboundOptions: List<NodeInbound> = emptyList(),
    val outboundTag: String = "",
    val outboundOptions: List<String> = emptyList(),
    val fieldError: ValidationField? = null,
    val testing: Boolean = false,
    val testResult: NodeTestResult? = null,
    val fetchingPin: Boolean = false,
    val fetchingInbounds: Boolean = false,
    val saving: Boolean = false,
    val error: AppError? = null,
    val saved: Boolean = false,
) {
    val isNew: Boolean get() = nodeId == 0
    val busy: Boolean get() = testing || fetchingPin || fetchingInbounds || saving
}

@HiltViewModel
class NodeEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NodeRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<NodeEditRoute>()
    private val serverId = route.serverId

    private val _state = MutableStateFlow(NodeEditState(nodeId = route.nodeId, loaded = route.nodeId == 0))
    val state: StateFlow<NodeEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.outboundTags(serverId) }.onSuccess { tags -> _state.update { it.copy(outboundOptions = tags) } }
        }
        if (route.nodeId != 0) {
            viewModelScope.launch {
                try {
                    val node = repository.get(serverId, route.nodeId)
                    _state.update { current ->
                        if (node == null) {
                            current.copy(loaded = true, error = AppError.PanelError("node ${route.nodeId} not found"))
                        } else {
                            current.copy(
                                loaded = true,
                                name = node.name,
                                remark = node.remark,
                                scheme = node.scheme,
                                address = node.address,
                                port = node.port.toString(),
                                basePath = node.basePath,
                                hasStoredToken = node.hasApiToken,
                                enabled = node.enabled,
                                allowPrivateAddress = node.allowPrivateAddress,
                                tlsVerifyMode = if (node.scheme == "http") "skip" else node.tlsVerifyMode,
                                pinnedCertSha256 = node.pinnedCertSha256,
                                inboundSyncMode = node.inboundSyncMode,
                                inboundTags = node.inboundTags,
                                inboundOptions = node.inboundTags.map { NodeInbound(it, "", "", 0) },
                                outboundTag = node.outboundTag,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(loaded = true, error = e.asAppError()) }
                }
            }
        }
    }

    fun onName(value: String) = edit { copy(name = value) }
    fun onRemark(value: String) = edit { copy(remark = value) }
    fun onScheme(value: String) = edit { copy(scheme = value, tlsVerifyMode = if (value == "http") "skip" else tlsVerifyMode) }
    fun onAddress(value: String) = edit { copy(address = value.trim()) }
    fun onPort(value: String) = edit { copy(port = value.filter(Char::isDigit).take(5)) }
    fun onBasePath(value: String) = edit { copy(basePath = value.trim()) }
    fun onApiToken(value: String) = edit { copy(apiToken = value.trim()) }
    fun onEnabled(value: Boolean) = edit { copy(enabled = value) }
    fun onAllowPrivate(value: Boolean) = edit { copy(allowPrivateAddress = value) }
    fun onTlsMode(value: String) = edit { copy(tlsVerifyMode = value) }
    fun onPin(value: String) = edit { copy(pinnedCertSha256 = value.trim()) }
    fun onSyncMode(value: String) = edit { copy(inboundSyncMode = value) }
    fun onOutboundTag(value: String) = edit { copy(outboundTag = value) }
    fun onToggleTag(tag: String) = edit { copy(inboundTags = if (tag in inboundTags) inboundTags - tag else inboundTags + tag) }

    fun test() {
        val draft = draftOrNull() ?: return
        _state.update { it.copy(testing = true, testResult = null, error = null) }
        viewModelScope.launch {
            val result = probe(draft)
            _state.update { it.copy(testing = false, testResult = result) }
        }
    }

    fun fetchPin() {
        val draft = draftOrNull() ?: return
        _state.update { it.copy(fetchingPin = true, error = null) }
        launchCatching({ it.copy(fetchingPin = false) }) {
            val pin = repository.fetchPin(serverId, draft)
            _state.update { it.copy(fetchingPin = false, pinnedCertSha256 = pin) }
        }
    }

    fun loadInbounds() {
        val draft = draftOrNull() ?: return
        _state.update { it.copy(fetchingInbounds = true, error = null) }
        launchCatching({ it.copy(fetchingInbounds = false) }) {
            val options = repository.remoteInbounds(serverId, draft)
            _state.update { it.copy(fetchingInbounds = false, inboundOptions = options) }
        }
    }

    /** Like the panel's form: test first and save only a node that answers. */
    fun save() {
        val draft = draftOrNull() ?: return
        _state.update { it.copy(saving = true, testResult = null, error = null) }
        launchCatching({ it.copy(saving = false) }) {
            val result = probe(draft)
            if (!result.online) {
                _state.update { it.copy(saving = false, testResult = result) }
                return@launchCatching
            }
            repository.save(serverId, draft)
            _state.update { it.copy(saving = false, testResult = result, saved = true) }
        }
    }

    private suspend fun probe(draft: NodeDraft): NodeTestResult = try {
        repository.test(serverId, draft)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NodeTestResult(online = false, error = "", latencyMs = 0, xrayState = "", xrayVersion = "", xrayError = "", panelVersion = "")
            .also { _state.update { s -> s.copy(error = e.asAppError()) } }
    }

    private fun launchCatching(onError: (NodeEditState) -> NodeEditState, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { onError(it).copy(error = e.asAppError()) }
            }
        }
    }

    private fun draftOrNull(): NodeDraft? {
        val s = _state.value
        val port = s.port.toIntOrNull()?.takeIf { it in 1..65535 }
        val error = when {
            s.name.isBlank() -> ValidationField.NAME
            s.address.isBlank() -> ValidationField.HOST
            port == null -> ValidationField.PORT
            else -> null
        }
        if (error != null || port == null) {
            _state.update { it.copy(fieldError = error) }
            return null
        }
        return NodeDraft(
            id = s.nodeId,
            name = s.name.trim(),
            remark = s.remark.trim(),
            scheme = s.scheme,
            address = normalizeAddress(s.address),
            port = port,
            basePath = s.basePath,
            apiToken = s.apiToken,
            enabled = s.enabled,
            allowPrivateAddress = s.allowPrivateAddress,
            tlsVerifyMode = s.tlsVerifyMode,
            pinnedCertSha256 = s.pinnedCertSha256,
            inboundSyncMode = s.inboundSyncMode,
            inboundTags = s.inboundTags,
            outboundTag = s.outboundTag,
        )
    }

    private fun edit(transform: NodeEditState.() -> NodeEditState) =
        _state.update { it.transform().copy(fieldError = null, testResult = null) }

    companion object {
        /** Accepts a pasted panel URL ("https://node.example.com:2053/path/") and keeps the host. */
        fun normalizeAddress(value: String): String =
            value.trim().substringAfter("://").substringBefore('/').let { host ->
                when {
                    host.startsWith("[") -> host.substringBefore(']') + "]"
                    host.count { it == ':' } > 1 -> host // bare IPv6
                    else -> host.substringBefore(':')
                }
            }
    }
}
