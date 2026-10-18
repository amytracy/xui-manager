package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.domain.model.DeployProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory live state of deploy jobs. The masked remote log is the durable copy; this is rebuilt on re-attach. */
@Singleton
class DeployProgressRegistry @Inject constructor() {
    private val flows = ConcurrentHashMap<Long, MutableStateFlow<DeployProgress?>>()
    private val attached: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun flow(jobId: Long): StateFlow<DeployProgress?> = mutable(jobId)

    fun tryAttach(jobId: Long): Boolean = attached.add(jobId)
    fun detach(jobId: Long) { attached.remove(jobId) }
    fun isAttached(jobId: Long): Boolean = jobId in attached
    fun anyAttached(): Boolean = attached.isNotEmpty()

    fun set(progress: DeployProgress) {
        mutable(progress.jobId).value = progress
    }

    fun update(jobId: Long, transform: (DeployProgress) -> DeployProgress) {
        mutable(jobId).update { current -> current?.let(transform) }
    }

    fun appendLog(jobId: Long, lines: List<String>) {
        if (lines.isEmpty()) return
        update(jobId) { it.copy(log = (it.log + lines).takeLast(DeployConfig.MAX_LOG_LINES)) }
    }

    private fun mutable(jobId: Long) = flows.getOrPut(jobId) { MutableStateFlow(null) }
}
