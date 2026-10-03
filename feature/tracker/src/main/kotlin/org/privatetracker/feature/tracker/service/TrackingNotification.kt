package org.privatetracker.feature.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import org.privatetracker.feature.tracker.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.privatetracker.core.designsystem.R as DesignR

/** The ongoing notification of the tracking service. It tells the tracked person that tracking is on. */
internal object TrackingNotification {
    const val ID = 1
    private const val CHANNEL = "tracking"
    private val TimeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun waiting(context: Context): Notification = build(context, context.getString(R.string.tracker_notification_waiting))

    fun lastFix(context: Context, at: Instant): Notification =
        build(context, context.getString(R.string.tracker_notification_last, TimeFormat.format(at.atZone(ZoneId.systemDefault()))))

    fun show(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }

    private fun build(context: Context, text: String): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.tracker_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(DesignR.drawable.ic_location)
            .setContentTitle(context.getString(R.string.tracker_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) })
            .build()
    }
}
