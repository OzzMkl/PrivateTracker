package org.privatetracker.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.app.server.ServerService
import org.privatetracker.app.tracker.TrackingService
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops the foreground services. Call only while the app is visible. */
@Singleton
class ServiceLauncher @Inject constructor(@ApplicationContext private val context: Context) {
    fun startServer() = ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))
    fun stopServer() = context.stopService(Intent(context, ServerService::class.java))
    fun startTracking() = ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
    fun stopTracking() = context.stopService(Intent(context, TrackingService::class.java))
}
