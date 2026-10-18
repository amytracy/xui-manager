package dev.autorasqr.xuimanager.ui.clients

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.data.deploy.RandomTokens
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.Client
import dev.autorasqr.xuimanager.domain.model.ClientDraft
import dev.autorasqr.xuimanager.domain.model.ClientsSnapshot
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.ClientRepository
import dev.autorasqr.xuimanager.domain.usecase.ClientDraftValidator
import dev.autorasqr.xuimanager.ui.common.Formatters
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.ClientsRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil

/** Create (originalEmail == null) or edit form. Untouched traffic/expiry fields keep the stored values exactly. */
data class ClientForm(
    val originalEmail: String?,
    val email: String,
    val trafficGb: String,
    val days: String,
    val subId: String,
    val enabled: Boolean,
    val inboundIds: Set<Int>,
    val originalTotalBytes: Long = 0,
    val originalExpiry: Long = 0,
    val trafficTouched: Boolean = originalEmail == null,
    val daysTouched: Boolean = originalEmail == null,
    val fieldError: ValidationField? = null,
    val busy: Boolean = false,
    val error: AppError? = null,
) {
    val isEdit: Boolean get() = originalEmail != null
}

data class SubscriptionSheet(
    val client: Client,
    val url: String? = null,
    val error: AppError? = null,
)

enum class ClientAction { DELETE, RESET_TRAFFIC }

data class PendingConfirmation(val action: ClientAction, val email: String)

/** One-shot feedback shown in a snackbar. */
data class UiMessage(@param:StringRes val text: Int? = null, val error: AppError? = null)

data class ClientsUiState(
    val snapshot: ClientsSnapshot? = null,
    val refreshing: Boolean = false,
    val error: AppError? = null,
    val form: ClientForm? = null,
    val subscription: SubscriptionSheet? = null,
    val confirmation: PendingConfirmation? = null,
    val message: UiMessage? = null,
)

@HiltViewModel
class ClientsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ClientRepository,
    private val random: RandomTokens,
) : ViewModel() {

    private val serverId = savedStateHandle.toRoute<ClientsRoute>().serverId
    private val _state = MutableStateFlow(ClientsUiState())
    val state: StateFlow<ClientsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.load(serverId)
                _state.update { it.copy(snapshot = snapshot, refreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, error = e.asAppError()) }
            }
        }
    }

    fun openCreate() {
        val snapshot = _state.value.snapshot ?: return
        val preselected = snapshot.defaultInboundId?.takeIf { id -> snapshot.inbounds.any { it.id == id } }
            ?: snapshot.inbounds.firstOrNull()?.id
        _state.update {
            it.copy(
                form = ClientForm(
                    originalEmail = null,
                    email = "",
                    trafficGb = "0",
                    days = "30",
                    subId = random.lowerAlphanumeric(16),
                    enabled = true,
                    inboundIds = setOfNotNull(preselected),
                ),
            )
        }
    }

    fun openEdit(client: Client) {
        val now = System.currentTimeMillis()
        val days = when {
            client.expiryTime == 0L -> 0L
            client.expiryTime < 0 -> -client.expiryTime / Formatters.DAY_MS
            else -> ceil((client.expiryTime - now).coerceAtLeast(0).toDouble() / Formatters.DAY_MS).toLong()
        }
        _state.update {
            it.copy(
                subscription = null,
                form = ClientForm(
                    originalEmail = client.email,
                    email = client.email,
                    trafficGb = Formatters.bytesToGigabytesText(client.totalBytes),
                    days = days.toString(),
                    subId = client.subId,
                    enabled = client.enabled,
                    inboundIds = client.inboundIds.toSet(),
                    originalTotalBytes = client.totalBytes,
                    originalExpiry = client.expiryTime,
                ),
            )
        }
    }

    fun closeForm() = _state.update { it.copy(form = null) }
    fun onEmail(value: String) = editForm { copy(email = value.trim(), fieldError = null) }
    fun onTraffic(value: String) = editForm {
        copy(trafficGb = value.replace(',', '.').filter { it.isDigit() || it == '.' }.take(8), trafficTouched = true, fieldError = null)
    }
    fun onDays(value: String) = editForm { copy(days = value.filter(Char::isDigit).take(5), daysTouched = true, fieldError = null) }
    fun onSubId(value: String) = editForm { copy(subId = value.trim(), fieldError = null) }
    fun regenerateSubId() = editForm { copy(subId = random.lowerAlphanumeric(16), fieldError = null) }
    fun onEnabled(value: Boolean) = editForm { copy(enabled = value) }

    fun onInboundToggled(id: Int) {
        val multi = _state.value.snapshot?.multiInbound ?: false
        editForm {
            val next = when {
                !multi -> setOf(id)
                id in inboundIds -> inboundIds - id
                else -> inboundIds + id
            }
            copy(inboundIds = next, fieldError = null)
        }
    }

    fun saveForm() {
        val form = _state.value.form ?: return
        val gb = form.trafficGb.ifEmpty { "0" }.toDoubleOrNull()
        val days = form.days.ifEmpty { "0" }.toLongOrNull()
        val numberError = when {
            gb == null || gb < 0 -> ValidationField.TRAFFIC
            days == null || days < 0 -> ValidationField.EXPIRY
            else -> null
        }
        if (numberError != null || gb == null || days == null) {
            editForm { copy(fieldError = numberError) }
            return
        }
        val draft = ClientDraft(
            email = form.email,
            totalBytes = if (form.trafficTouched) Formatters.gigabytesToBytes(gb) else form.originalTotalBytes,
            expiryTime = when {
                !form.daysTouched -> form.originalExpiry
                days > 0 -> System.currentTimeMillis() + days * Formatters.DAY_MS
                else -> 0L
            },
            subId = form.subId,
            inboundIds = form.inboundIds.sorted(),
            enabled = form.enabled,
        )
        val others = _state.value.snapshot?.clients.orEmpty().map { it.email }
            .filterNot { form.originalEmail != null && it.equals(form.originalEmail, ignoreCase = true) }
        ClientDraftValidator.validate(draft, others)?.let { field ->
            editForm { copy(fieldError = field) }
            return
        }
        editForm { copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (form.originalEmail == null) {
                    repository.createClient(serverId, draft)
                } else {
                    repository.updateClient(serverId, form.originalEmail, draft)
                }
                val snapshot = repository.load(serverId)
                _state.update {
                    it.copy(snapshot = snapshot, form = null, message = if (form.isEdit) UiMessage(R.string.client_saved) else null)
                }
                if (!form.isEdit) {
                    snapshot.clients.firstOrNull { it.email.equals(draft.email, ignoreCase = true) }?.let(::openSubscription)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.asAppError()
                editForm {
                    if (error is AppError.Validation) copy(busy = false, fieldError = error.field) else copy(busy = false, error = error)
                }
            }
        }
    }

    fun openSubscription(client: Client) {
        _state.update { it.copy(subscription = SubscriptionSheet(client)) }
        viewModelScope.launch {
            val sheet = try {
                SubscriptionSheet(client, url = repository.subscriptionUrl(serverId, client.subId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SubscriptionSheet(client, error = e.asAppError())
            }
            _state.update { if (it.subscription?.client?.email == client.email) it.copy(subscription = sheet) else it }
        }
    }

    fun closeSubscription() = _state.update { it.copy(subscription = null) }

    fun setEnabled(client: Client, enabled: Boolean) {
        _state.update { it.copy(subscription = null) }
        perform(if (enabled) R.string.client_enabled_message else R.string.client_disabled_message) {
            repository.setEnabled(serverId, client.email, enabled)
        }
    }

    fun requestConfirmation(action: ClientAction, client: Client) =
        _state.update { it.copy(confirmation = PendingConfirmation(action, client.email)) }

    fun dismissConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val pending = _state.value.confirmation ?: return
        _state.update { it.copy(confirmation = null, subscription = null) }
        when (pending.action) {
            ClientAction.DELETE -> perform(R.string.client_deleted_message) { repository.deleteClient(serverId, pending.email) }
            ClientAction.RESET_TRAFFIC -> perform(R.string.client_reset_message) { repository.resetTraffic(serverId, pending.email) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Runs a list-changing action, reloads the list and reports the outcome in a snackbar. */
    private fun perform(@StringRes success: Int, action: suspend () -> Unit) {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val message = try {
                action()
                UiMessage(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiMessage(error = e.asAppError())
            }
            val snapshot = try {
                repository.load(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update { it.copy(snapshot = snapshot ?: it.snapshot, refreshing = false, message = message) }
        }
    }

    private fun editForm(transform: ClientForm.() -> ClientForm) =
        _state.update { state -> state.form?.let { state.copy(form = it.transform()) } ?: state }
}
