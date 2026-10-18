package dev.autorasqr.xuimanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.autorasqr.xuimanager.MainActivity
import dev.autorasqr.xuimanager.R
import dev.autorasqr.xuimanager.domain.model.DeployProgress
import dev.autorasqr.xuimanager.domain.model.DeployState
import dev.autorasqr.xuimanager.ui.common.labelRes
import dev.autorasqr.xuimanager.ui.common.message

object DeployNotifications {
    private const val CHANNEL_ID = "deploy"
    const val ONGOING_ID = 1
    private const val RESULT_ID_BASE = 10_000

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun ongoing(context: Context, progress: DeployProgress?): Notification =
        base(context)
            .setContentTitle(context.getString(R.string.notif_deploying))
            .setContentText(progress?.let { context.getString(it.stage.labelRes()) })
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Posted when a job ends; silently skipped if notifications are not permitted. */
    fun showResult(context: Context, progress: DeployProgress) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val succeeded = progress.state == DeployState.SUCCEEDED
        val notification = base(context)
            .setContentTitle(context.getString(if (succeeded) R.string.notif_done else R.string.notif_failed))
            .setContentText(progress.error?.message(context))
            .setStyle(NotificationCompat.BigTextStyle().bigText(progress.error?.message(context)))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(RESULT_ID_BASE + progress.jobId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call.
        }
    }

    private fun base(context: Context): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }
}
