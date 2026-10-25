package dev.autorasqr.xuimanager.ui.servers

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.ServerDraft
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import dev.autorasqr.xuimanager.domain.model.ValidationField
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import dev.autorasqr.xuimanager.domain.usecase.ServerDraftValidator
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.common.readNBytesCompat
import dev.autorasqr.xuimanager.ui.navigation.ServerEditRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HostKeyPrompt(val host: String, val presented: HostKeyPin, val previous: HostKeyPin?)

data class ServerEditState(
    val serverId: Long?,
    /** Set when the server is (or is being added as) a node of that server's panel. */
    val masterServerId: Long? = null,
    /** A node already on the master panel that this new server restores rather than deploys. */
    val restoreNodeId: Int? = null,
    val loaded: Boolean = false,
    val name: String = "",
    val host: String = "",
    val domain: String = "",
    val port: String = "22",
    val user: String = "root",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val hasStoredPassword: Boolean = false,
    val hasStoredKey: Boolean = false,
    val fieldError: ValidationField? = null,
    val busy: Boolean = false,
    val error: AppError? = null,
    val offerSaveWithoutCheck: Boolean = false,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val keyFileError: Boolean = false,
    val result: Result? = null,
) {
    sealed interface Result {
        data class Created(val id: Long) : Result
        data object Updated : Result
        data class Deleted(val wasNode: Boolean) : Result
    }
}

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ServerRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ServerEditRoute>()
    private val routeId = route.serverId.takeIf { it > 0 }
    private val _state = MutableStateFlow(
        ServerEditState(
            serverId = routeId,
            masterServerId = route.masterServerId.takeIf { it > 0 },
            restoreNodeId = route.restoreNodeId.takeIf { it > 0 && routeId == null },
            loaded = routeId == null,
            // The node's panel address is usually the SSH host too (an IP or the server's domain).
            name = route.name,
            host = route.host,
        ),
    )
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    private var original: Server? = null

    init {
        if (routeId != null) {
            viewModelScope.launch {
                val server = repository.getServer(routeId)
                original = server
                val storedPassword = server != null &&
                    withContext(Dispatchers.IO) { repository.hasStoredSecret(server.id, SshAuthType.PASSWORD) }
                val storedKey = server != null &&
                    withContext(Dispatchers.IO) { repository.hasStoredSecret(server.id, SshAuthType.PRIVATE_KEY) }
                _state.update { current ->
                    if (server == null) {
                        current.copy(loaded = true)
                    } else {
                        current.copy(
                            loaded = true,
                            masterServerId = server.masterServerId,
                            name = server.name,
                            host = server.host,
                            domain = server.domain.orEmpty(),
                            port = server.sshPort.toString(),
                            user = server.sshUser,
                            authType = server.authType,
                            hasStoredPassword = storedPassword,
                            hasStoredKey = storedKey,
                        )
                    }
                }
            }
        }
    }

    fun onName(value: String) = edit { copy(name = value) }
    fun onHost(value: String) = edit { copy(host = value.trim()) }

    fun onDomain(value: String) = edit { copy(domain = value.trim()) }
    fun onPort(value: String) = edit { copy(port = value.filter(Char::isDigit).take(5)) }
    fun onUser(value: String) = edit { copy(user = value.trim()) }
    fun onAuthType(value: SshAuthType) = edit { copy(authType = value) }
    fun onPassword(value: String) = edit { copy(password = value) }
    fun onPrivateKey(value: String) = edit { copy(privateKey = value, keyFileError = false) }
    fun onPassphrase(value: String) = edit { copy(passphrase = value) }
    fun dismissError() = _state.update { it.copy(error = null, offerSaveWithoutCheck = false) }

    fun loadKeyFile(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { input -> input.readNBytesCompat(MAX_KEY_FILE) }?.decodeToString()
                }.getOrNull()
            }
            _state.update { if (text.isNullOrBlank()) it.copy(keyFileError = true) else it.copy(privateKey = text, keyFileError = false) }
        }
    }

    /** Validates, confirms the host key when needed, checks the login and saves. */
    fun save() {
        val current = _state.value
        val draft = current.toDraft() ?: return
        val stored = when (draft.authType) {
            SshAuthType.PASSWORD -> current.hasStoredPassword
            SshAuthType.PRIVATE_KEY -> current.hasStoredKey
        }
        ServerDraftValidator.validate(draft, stored)?.let { field ->
            _state.update { it.copy(fieldError = field) }
            return
        }
        _state.update { it.copy(fieldError = null, busy = true, error = null, offerSaveWithoutCheck = false) }
        viewModelScope.launch {
            try {
                val previous = original
                val sameEndpoint = previous != null && previous.host == draft.host && previous.sshPort == draft.sshPort
                val pinned = previous?.hostKey?.takeIf { sameEndpoint }
                if (pinned != null) {
                    repository.verifyLogin(draft, pinned)
                    finishSave(draft, pinned)
                } else {
                    val presented = repository.fetchHostKey(draft.host, draft.sshPort)
                    _state.update {
                        it.copy(
                            busy = false,
                            hostKeyPrompt = HostKeyPrompt(draft.host, presented, previous?.hostKey.takeIf { sameEndpoint }),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.asAppError())
            }
        }
    }

    fun trustHostKey() {
        val prompt = _state.value.hostKeyPrompt ?: return
        val draft = _state.value.toDraft() ?: return
        _state.update { it.copy(hostKeyPrompt = null, busy = true) }
        viewModelScope.launch {
            try {
                repository.verifyLogin(draft, prompt.presented)
                finishSave(draft, prompt.presented)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.asAppError())
            }
        }
    }

    fun dismissHostKey() = _state.update { it.copy(hostKeyPrompt = null, busy = false) }

    /** For servers that are unreachable right now; the host key is confirmed on the first real connection. */
    fun saveWithoutCheck() {
        val draft = _state.value.toDraft() ?: return
        _state.update { it.copy(busy = true, error = null, offerSaveWithoutCheck = false) }
        viewModelScope.launch {
            try {
                finishSave(draft, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.asAppError())
            }
        }
    }

    fun delete() {
        val id = routeId ?: return
        viewModelScope.launch {
            repository.delete(id)
            _state.update { it.copy(result = ServerEditState.Result.Deleted(wasNode = it.masterServerId != null)) }
        }
    }

    private suspend fun finishSave(draft: ServerDraft, pin: HostKeyPin?) {
        val id = repository.save(draft, pin)
        _state.update {
            it.copy(
                busy = false,
                result = if (routeId == null) ServerEditState.Result.Created(id) else ServerEditState.Result.Updated,
            )
        }
    }

    private fun fail(error: AppError) {
        val offline = error is AppError.Timeout || error is AppError.ConnectionRefused ||
            error is AppError.NetworkUnreachable || error is AppError.UnknownHost
        _state.update { it.copy(busy = false, error = error, offerSaveWithoutCheck = offline) }
    }

    private fun edit(transform: ServerEditState.() -> ServerEditState) = _state.update { it.transform().copy(fieldError = null) }

    private fun ServerEditState.toDraft(): ServerDraft? {
        val sshPort = port.toIntOrNull() ?: run {
            _state.update { it.copy(fieldError = ValidationField.PORT) }
            return null
        }
        return ServerDraft(
            id = serverId,
            name = name,
            host = host,
            sshPort = sshPort,
            sshUser = user,
            authType = authType,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            domain = normalizeDomain(domain),
            masterServerId = masterServerId,
            nodeId = restoreNodeId,
        )
    }

    companion object {
        private const val MAX_KEY_FILE = 64 * 1024

        /** Accepts a pasted URL too ("https://vpn.example.com/"): keeps only the host name. */
        fun normalizeDomain(value: String): String =
            value.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore(':').trimEnd('.')
    }
}
