package dev.autorasqr.xuimanager.data.deploy

/** Starts the foreground service that runs [DeployOrchestrator.run] for a job. */
fun interface DeployLauncher {
    fun start(jobId: Long)
}
