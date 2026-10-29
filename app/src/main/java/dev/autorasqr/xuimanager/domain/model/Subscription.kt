package dev.autorasqr.xuimanager.domain.model

/**
 * One JSON-subscription balancer (3x-ui v3 sub-balancers): an extra profile that picks the best endpoint of
 * [inboundIds]. [memberWeights] only matters for leastLoad; [id] is 0 for one not saved yet.
 */
data class SubBalancer(
    val id: Int,
    val remark: String,
    val strategy: String,
    val inboundIds: List<Int>,
    val memberWeights: Map<Int, Double>,
    val sortOrder: Int,
    val enabled: Boolean,
)
