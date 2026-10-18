package dev.autorasqr.xuimanager.ui.inbounds

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.SniPresets
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.DEFAULT_FINGERPRINT
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.domain.model.InboundItem
import dev.autorasqr.xuimanager.domain.model.InboundsSnapshot
import dev.autorasqr.xuimanager.domain.model.RealityDraft
import dev.autorasqr.xuimanager.domain.model.SelfStealAction
import dev.autorasqr.xuimanager.domain.model.SelfStealProgress
import dev.autorasqr.xuimanager.domain.model.SniCheck
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.InboundRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.InboundsRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick VLESS + TCP + Reality form; [editingId] is null when creating. */
data class RealityForm(
    val editingId: Int? = null,
    val remark: String = "",
    val port: String = "443",
    val sni: String = SniPresets.targets.first(),
    val fingerprint: String = DEFAULT_FINGERPRINT,
    val fieldError: ValidationField? = null,
    val error: AppError? = null,
    val saving: Boolean = false,
    val checking: Boolean = false,
    val check: SniCheck? = null,
    /** Node to create the inbound on; null for the panel's own server. */
    val nodeId: Int? = null,
)

data class SelfStealForm(
    /** Reality inbounds on port 443 that can be moved onto the decoy site, keeping their clients. */
    val candidates: List<InboundItem>,
    /** Null creates a new inbound. */
    val convertId: Int?,
    val fingerprint: String = DEFAULT_FINGERPRINT,
)

data class InboundsUiState(
    val snapshot: InboundsSnapshot? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val busyIds: Set<Int> = emptySet(),
    @param:StringRes val message: Int? = null,
    val addMenu: Boolean = false,
    val realityForm: RealityForm? = null,
    val selfStealForm: SelfStealForm? = null,
    val confirmDelete: InboundItem? = null,
    /** After the last self-steal inbound is deleted: offer to remove the decoy site too. */
    val offerRemoveSite: Boolean = false,
    val selfSteal: SelfStealProgress? = null,
)

@HiltViewModel
class InboundsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: InboundRepository,
) : ViewModel() {

    val serverId: Long = savedStateHandle.toRoute<InboundsRoute>().serverId
    private val _state = MutableStateFlow(InboundsUiState())
    val state: StateFlow<InboundsUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            var previous: DeployState? = null
            repository.selfStealProgress(serverId).collect { progress ->
                _state.update { it.copy(selfSteal = progress) }
                // Reload once a run finishes so the list shows the new or moved inbound.
                if (previous == DeployState.RUNNING && progress?.state != DeployState.RUNNING) refresh()
                previous = progress?.state
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val snapshot = repository.load(serverId)
                _state.update { it.copy(snapshot = snapshot, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asAppError()) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setEnabled(item: InboundItem, enabled: Boolean) = perform(item.id, if (enabled) R.string.inbound_enabled else R.string.inbound_disabled) {
        repository.setEnabled(serverId, item.id, enabled)
    }

    fun requestDelete(item: InboundItem) = _state.update { it.copy(confirmDelete = item) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val item = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        perform(item.id, R.string.inbound_deleted) {
            repository.delete(serverId, item.id)
            val snapshot = _state.value.snapshot
            val othersUseSite = snapshot?.items.orEmpty().any { it.selfSteal && it.id != item.id }
            if (item.selfSteal && snapshot?.selfStealPort != null && !othersUseSite) {
                _state.update { it.copy(offerRemoveSite = true) }
            }
        }
    }

    fun dismissRemoveSite() = _state.update { it.copy(offerRemoveSite = false) }

    fun removeSite() {
        _state.update { it.copy(offerRemoveSite = false) }
        repository.removeSelfSteal(serverId)
    }

    // --- add menu ---

    fun openAddMenu() = _state.update { it.copy(addMenu = true) }
    fun closeAddMenu() = _state.update { it.copy(addMenu = false) }

    fun openCreateReality() {
        val port = freePort(nodeId = null)
        _state.update { it.copy(addMenu = false, realityForm = RealityForm(remark = "reality-$port", port = port.toString())) }
    }

    /** Ports are per machine, so moving the new inbound to another node may free 443 or make the port collide. */
    fun onNode(nodeId: Int?) = editForm {
        val autoRemark = remark == "reality-$port"
        val newPort = port.toIntOrNull()?.takeIf { p -> !portUsed(p, nodeId) } ?: freePort(nodeId)
        copy(
            nodeId = nodeId,
            port = newPort.toString(),
            remark = if (autoRemark) "reality-$newPort" else remark,
            check = null,
            fieldError = null,
            error = null,
        )
    }

    private fun portUsed(port: Int, nodeId: Int?) =
        _state.value.snapshot?.items.orEmpty().any { it.port == port && it.nodeId == nodeId }

    private fun freePort(nodeId: Int?): Int =
        if (!portUsed(443, nodeId)) 443 else (20000..60000).random().let { p -> generateSequence(p) { it + 1 }.first { !portUsed(it, nodeId) } }

    fun openEditReality(item: InboundItem) {
        val reality = item.reality ?: return
        _state.update {
            it.copy(
                realityForm = RealityForm(
                    editingId = item.id,
                    remark = item.remark,
                    port = item.port.toString(),
                    sni = reality.sni,
                    fingerprint = reality.fingerprint.ifBlank { DEFAULT_FINGERPRINT },
                    nodeId = item.nodeId,
                ),
            )
        }
    }

    fun closeRealityForm() = _state.update { it.copy(realityForm = null) }
    fun onRemark(value: String) = editForm { copy(remark = value.take(64), fieldError = null) }
    fun onPort(value: String) = editForm { copy(port = value.filter(Char::isDigit).take(5), fieldError = null, error = null) }
    fun onSni(value: String) = editForm { copy(sni = value.trim().lowercase(), fieldError = null, check = null, error = null) }
    fun onFingerprint(value: String) = editForm { copy(fingerprint = value) }

    fun checkSni() {
        val form = _state.value.realityForm ?: return
        editForm { copy(checking = true, check = null, error = null) }
        viewModelScope.launch {
            try {
                val check = repository.checkSni(serverId, form.sni, form.nodeId)
                editForm { copy(checking = false, check = check) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failForm(e.asAppError())
            }
        }
    }

    fun saveReality() {
        val form = _state.value.realityForm ?: return
        val port = form.port.toIntOrNull()
        if (form.editingId == null && port == null) {
            editForm { copy(fieldError = ValidationField.INBOUND_PORT) }
            return
        }
        editForm { copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (form.editingId != null) {
                    repository.updateReality(serverId, form.editingId, form.sni, form.fingerprint)
                } else {
                    repository.createReality(serverId, RealityDraft(form.remark, port!!, form.sni, form.fingerprint, form.nodeId))
                }
                _state.update {
                    it.copy(realityForm = null, message = if (form.editingId != null) R.string.inbound_updated else R.string.inbound_created)
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failForm(e.asAppError())
            }
        }
    }

    // --- self-steal ---

    fun openSelfSteal() {
        val items = _state.value.snapshot?.items.orEmpty()
        val candidates = items.filter { it.quickEditable && it.port == 443 && !it.selfSteal && it.nodeId == null }
        _state.update { it.copy(addMenu = false, selfStealForm = SelfStealForm(candidates, candidates.firstOrNull()?.id)) }
    }

    fun closeSelfSteal() = _state.update { it.copy(selfStealForm = null) }
    fun onConvert(id: Int?) = _state.update { it.copy(selfStealForm = it.selfStealForm?.copy(convertId = id)) }
    fun onSelfStealFingerprint(value: String) = _state.update { it.copy(selfStealForm = it.selfStealForm?.copy(fingerprint = value)) }

    fun startSelfSteal() {
        val form = _state.value.selfStealForm ?: return
        _state.update { it.copy(selfStealForm = null) }
        repository.installSelfSteal(serverId, form.convertId, form.fingerprint)
    }

    fun dismissSelfStealProgress() = repository.clearSelfStealProgress(serverId)

    val selfStealRunning: Boolean get() = _state.value.selfSteal?.state == DeployState.RUNNING

    private fun perform(id: Int, @StringRes success: Int, action: suspend () -> Unit) {
        _state.update { it.copy(busyIds = it.busyIds + id) }
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(busyIds = it.busyIds - id, message = success) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busyIds = it.busyIds - id, error = e.asAppError()) }
            }
        }
    }

    private fun editForm(transform: RealityForm.() -> RealityForm) =
        _state.update { it.copy(realityForm = it.realityForm?.transform()) }

    private fun failForm(error: AppError) = editForm {
        if (error is AppError.Validation) copy(saving = false, checking = false, fieldError = error.field)
        else copy(saving = false, checking = false, error = error)
    }

    companion object {
        val SelfStealProgress.isInstall: Boolean get() = action == SelfStealAction.INSTALL
    }
}
