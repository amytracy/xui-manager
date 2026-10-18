package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.ClientDraft
import dev.autorasqr.xuimanager.domain.model.ClientsSnapshot

interface ClientRepository {
    suspend fun load(serverId: Long): ClientsSnapshot
    suspend fun createClient(serverId: Long, draft: ClientDraft)

    /** Replaces the client's editable fields; [originalEmail] identifies it, [draft] may rename it. */
    suspend fun updateClient(serverId: Long, originalEmail: String, draft: ClientDraft)
    suspend fun deleteClient(serverId: Long, email: String)
    suspend fun setEnabled(serverId: Long, email: String, enabled: Boolean)
    suspend fun resetTraffic(serverId: Long, email: String)
    suspend fun subscriptionUrl(serverId: Long, subId: String): String
}
