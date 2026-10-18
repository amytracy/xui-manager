package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.HostKeyPin
import dev.autorasqr.xuimanager.domain.model.PanelLogin
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.model.ServerDraft
import dev.autorasqr.xuimanager.domain.model.SshAuthType
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    fun observeServers(): Flow<List<Server>>
    fun observeServer(id: Long): Flow<Server?>
    suspend fun getServer(id: Long): Server?

    /** Connects without authenticating and returns the key the server presents, for the user to confirm. */
    suspend fun fetchHostKey(host: String, port: Int): HostKeyPin

    /** Logs in with the draft's credentials against the confirmed host key. Throws AppException on failure. */
    suspend fun verifyLogin(draft: ServerDraft, pin: HostKeyPin)

    /** Whether a password or key for [authType] is already saved, so the form may leave the field blank. */
    fun hasStoredSecret(serverId: Long, authType: SshAuthType): Boolean

    suspend fun save(draft: ServerDraft, pin: HostKeyPin?): Long
    /** The panel's address and sign-in details for showing to the user; null if the panel is not deployed. */
    suspend fun panelCredentials(serverId: Long): PanelLogin?

    suspend fun pinHostKey(serverId: Long, pin: HostKeyPin)
    suspend fun delete(id: Long)
}
