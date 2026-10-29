package dev.autorasqr.xuimanager.ui.subscription

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.data.subscription.SubscriptionDraft
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.InboundSummary
import dev.autorasqr.xuimanager.domain.model.SubBalancer
import dev.autorasqr.xuimanager.domain.repository.SubscriptionRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.SubscriptionRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionUiState(
    val loading: Boolean = true,
    val unsupported: Boolean = false,
    val draft: InboundDraft? = null,
    val original: InboundDraft? = null,
    /** Keys of the panel's AllSetting; only these are shown and written. */
    val known: Set<String> = emptySet(),
    val tab: SubTab = SubTab.PANEL,
    /** Saving and the panel restart that follows. */
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
    val balancers: List<SubBalancer> = emptyList(),
    val balancerInbounds: List<InboundSummary> = emptyList(),
    /** The balancer dialog; id 0 for a new one. */
    val editingBalancer: SubBalancer? = null,
    val balancerError: AppError? = null,
    val balancerBusy: Boolean = false,
    val confirmDeleteBalancer: SubBalancer? = null,
) {
    val dirty: Boolean get() = draft != null && original != null && SubscriptionDraft.changes(original, draft, known).isNotEmpty()
}

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SubscriptionRepository,
) : ViewModel() {
    val serverId: Long = savedStateHandle.toRoute<SubscriptionRoute>().serverId

    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (!repository.supported(serverId)) {
                    _state.update { it.copy(loading = false, unsupported = true) }
                    return@launch
                }
                val settings = repository.settings(serverId)
                val draft = SubscriptionDraft.fromPanel(settings)
                _state.update { it.copy(loading = false, draft = draft, original = draft, known = settings.keys, saved = false) }
                loadBalancers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asAppError()) }
            }
        }
    }

    fun selectTab(tab: SubTab) = _state.update { it.copy(tab = tab) }

    fun update(transform: (InboundDraft) -> InboundDraft) = _state.update { s ->
        val draft = s.draft ?: return@update s
        s.copy(draft = transform(draft), saved = false)
    }

    fun save() {
        val s = _state.value
        val original = s.original ?: return
        val draft = s.draft?.let(::normalizePaths) ?: return
        if (s.saving) return
        val changes = SubscriptionDraft.changes(original, draft, s.known)
        if (changes.isEmpty()) return
        _state.update { it.copy(saving = true, error = null, draft = draft) }
        viewModelScope.launch {
            try {
                repository.save(serverId, changes)
                _state.update { it.copy(saving = false, saved = true, original = draft) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.asAppError()) }
            }
        }
    }

    /** sanitizePath/normalizePath of the panel: a path starts and ends with "/". */
    private fun normalizePaths(d: InboundDraft): InboundDraft = PATH_KEYS.fold(d) { acc, key ->
        val path = JsonPath(key)
        val value = acc[path].str() ?: return@fold acc
        val trimmed = value.trim().replace(Regex("\\s+"), "").trim('/')
        val normalized = if (trimmed.isEmpty()) "/" else "/$trimmed/"
        if (normalized == value || value.isEmpty()) acc else acc.set(path, json(normalized))
    }

    // --- balancers: saved right away, like the panel's own table ---

    private suspend fun loadBalancers() {
        try {
            val balancers = repository.balancers(serverId)
            val inbounds = repository.balancerInbounds(serverId)
            _state.update { it.copy(balancers = balancers.sortedBy { b -> b.sortOrder }, balancerInbounds = inbounds, balancerError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(balancerError = e.asAppError()) }
        }
    }

    fun newBalancer() = _state.update {
        it.copy(editingBalancer = SubBalancer(0, "", "random", emptyList(), emptyMap(), sortOrder = 1, enabled = true), balancerError = null)
    }

    fun editBalancer(balancer: SubBalancer) = _state.update { it.copy(editingBalancer = balancer, balancerError = null) }
    fun changeBalancer(balancer: SubBalancer) = _state.update { it.copy(editingBalancer = balancer) }
    fun closeBalancer() = _state.update { it.copy(editingBalancer = null) }

    fun saveBalancer() {
        val balancer = _state.value.editingBalancer ?: return
        if (balancer.remark.isBlank() || balancer.inboundIds.isEmpty() || balancer.sortOrder < 1) return
        balancerAction {
            repository.saveBalancer(serverId, balancer)
            _state.update { it.copy(editingBalancer = null) }
        }
    }

    fun setBalancerEnabled(balancer: SubBalancer, enabled: Boolean) = balancerAction {
        repository.saveBalancer(serverId, balancer.copy(enabled = enabled))
    }

    fun requestDeleteBalancer(balancer: SubBalancer) = _state.update { it.copy(confirmDeleteBalancer = balancer) }
    fun dismissDeleteBalancer() = _state.update { it.copy(confirmDeleteBalancer = null) }

    fun deleteBalancer() {
        val balancer = _state.value.confirmDeleteBalancer ?: return
        _state.update { it.copy(confirmDeleteBalancer = null) }
        balancerAction { repository.deleteBalancer(serverId, balancer.id) }
    }

    private fun balancerAction(block: suspend () -> Unit) {
        if (_state.value.balancerBusy) return
        _state.update { it.copy(balancerBusy = true, balancerError = null) }
        viewModelScope.launch {
            try {
                block()
                loadBalancers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(balancerError = e.asAppError()) }
            } finally {
                _state.update { it.copy(balancerBusy = false) }
            }
        }
    }

    private companion object {
        val PATH_KEYS = listOf("subPath", "subJsonPath", "subClashPath")
    }
}
