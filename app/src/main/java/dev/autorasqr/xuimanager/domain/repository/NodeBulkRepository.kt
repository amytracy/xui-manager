package dev.autorasqr.xuimanager.domain.repository

import dev.autorasqr.xuimanager.domain.model.NodeAction
import dev.autorasqr.xuimanager.domain.model.NodeBulkProgress
import kotlinx.coroutines.flow.StateFlow

/** Runs one [NodeAction] on every node of the panel on server [masterId], one node after another. */
interface NodeBulkRepository {
    /** Runs in the background; a node that fails is logged and the rest still get their turn. */
    fun start(masterId: Long, action: NodeAction)
    fun progress(masterId: Long): StateFlow<NodeBulkProgress?>
    fun clear(masterId: Long)
}
