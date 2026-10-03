package org.privatetracker.feature.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import org.privatetracker.feature.server.R
import org.privatetracker.core.designsystem.R as DesignR

/** The ongoing notification of the server service. */
internal object ServerNotification {
    const val ID = 2
    private const val CHANNEL = "server"

    fun build(context: Context, text: String): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(DesignR.drawable.ic_server)
            .setContentTitle(context.getString(R.string.server_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) })
            .build()
    }

    fun show(context: Context, text: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, text))
    }
}
