package dev.autorasqr.xuimanager.domain.model

data class ServerStatus(
    val cpuPercent: Double,
    val memUsed: Long,
    val memTotal: Long,
    /** 1, 5 and 15 minute load averages; may be empty if the panel does not report them. */
    val loads: List<Double>,
    val uptimeSeconds: Long,
    /** "running", "stop" or "error" as reported by the panel. */
    val xrayState: String,
    val xrayVersion: String,
    val xrayError: String?,
    val panelVersion: String,
)
