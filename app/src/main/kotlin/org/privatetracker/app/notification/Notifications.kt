package org.privatetracker.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.privatetracker.app.MainActivity

/** Ongoing notifications of the two foreground services. Tracking is always visible to the tracked person. */
object Notifications {
    const val ID_TRACKING = 1
    const val ID_SERVER = 2
    private const val CHANNEL_TRACKING = "tracking"
    private const val CHANNEL_SERVER = "server"

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_TRACKING, "Rastreo de ubicación", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_SERVER, "Servidor", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun tracking(context: Context, text: String): Notification =
        build(context, CHANNEL_TRACKING, "Compartiendo tu ubicación", text)

    fun server(context: Context, text: String): Notification =
        build(context, CHANNEL_SERVER, "Servidor PrivateTracker", text)

    fun update(context: Context, id: Int, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun build(context: Context, channel: String, title: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
}
