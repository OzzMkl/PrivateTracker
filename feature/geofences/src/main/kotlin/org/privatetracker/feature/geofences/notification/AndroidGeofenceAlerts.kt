package org.privatetracker.feature.geofences.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.feature.geofences.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton
import org.privatetracker.core.designsystem.R as DesignR

/**
 * One notification per crossing, on a channel of their own so the owner can mute it apart from the
 * server's ongoing one. Without the notification permission, or with the app or the channel turned off,
 * nothing shows; the events stay in the list, marked as silent.
 */
@Singleton
class AndroidGeofenceAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
) : GeofenceAlerts {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    override fun canAlert(): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        // A channel not created yet will be, at the default importance.
        val channelOn = manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        permitted() && manager.areNotificationsEnabled() && channelOn
    } catch (e: RuntimeException) {
        Log.w(TAG, "Notification state unknown", e)
        false
    }

    override fun alert(events: List<GeofenceEventView>) {
        if (!permitted()) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.geofence_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
            for (view in events) {
                val event = view.event
                val title = context.getString(
                    if (event.transition == GeofenceTransition.ENTER) R.string.geofence_alert_enter else R.string.geofence_alert_exit,
                    view.deviceName,
                    view.geofenceName,
                )
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(DesignR.drawable.ic_location)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.geofence_alert_at, time.format(event.occurredAt.atZone(ZoneId.systemDefault()))))
                    .setWhen(event.occurredAt.toEpochMilli())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .build()
                // One per device and geofence: a newer crossing replaces the older one.
                manager.notify("${event.geofenceId}/${event.deviceId}", ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification refused", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Notification failed", e)
        }
    }

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL = "geofences"
        const val ID = 3
        const val TAG = "GeofenceAlerts"
    }
}
