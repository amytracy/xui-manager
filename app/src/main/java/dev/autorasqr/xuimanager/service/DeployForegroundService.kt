package dev.autorasqr.xuimanager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.autorasqr.xuimanager.data.deploy.DeployLauncher
import dev.autorasqr.xuimanager.data.deploy.DeployOrchestrator
import dev.autorasqr.xuimanager.data.deploy.DeployProgressRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Keeps deployments alive while the app is in the background. The remote script runs detached on the server,
 * so even if this service is killed the deployment continues and can be re-attached later.
 */
@AndroidEntryPoint
class DeployForegroundService : Service() {

    @Inject lateinit var orchestrator: DeployOrchestrator
    @Inject lateinit var registry: DeployProgressRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeJobs = mutableSetOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        val jobId = intent?.getLongExtra(EXTRA_JOB_ID, -1L) ?: -1L
        if (jobId <= 0 || !activeJobs.add(jobId)) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        scope.launch {
            val notifier = launch {
                registry.flow(jobId).filterNotNull().collect { progress ->
                    NotificationManagerCompat.from(this@DeployForegroundService).let { manager ->
                        if (manager.areNotificationsEnabled()) {
                            runCatching { manager.notify(DeployNotifications.ONGOING_ID, DeployNotifications.ongoing(this@DeployForegroundService, progress)) }
                        }
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) { orchestrator.run(jobId) }
            } finally {
                notifier.cancel()
                activeJobs.remove(jobId)
                registry.flow(jobId).value?.let { DeployNotifications.showResult(this@DeployForegroundService, it) }
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, DeployNotifications.ONGOING_ID, DeployNotifications.ongoing(this, null), type)
    }

    private fun stopIfIdle() {
        if (activeJobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        private const val EXTRA_JOB_ID = "job_id"

        fun intent(context: Context, jobId: Long): Intent =
            Intent(context, DeployForegroundService::class.java).putExtra(EXTRA_JOB_ID, jobId)
    }
}

class ServiceDeployLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeployLauncher {
    override fun start(jobId: Long) {
        ContextCompat.startForegroundService(context, DeployForegroundService.intent(context, jobId))
    }
}
