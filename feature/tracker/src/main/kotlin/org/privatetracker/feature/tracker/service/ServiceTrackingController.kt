package org.privatetracker.feature.tracker.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.TrackerActivity
import org.privatetracker.core.domain.port.TrackingController
import javax.inject.Inject
import javax.inject.Singleton

/** Starts [TrackingService] and holds what it reports, for the screen and the use cases. */
@Singleton
class ServiceTrackingController @Inject constructor(
    @ApplicationContext private val context: Context,
) : TrackingController {
    private val _activity = MutableStateFlow(TrackerActivity())
    override val activity: StateFlow<TrackerActivity> = _activity.asStateFlow()

    internal fun update(transform: (TrackerActivity) -> TrackerActivity) = _activity.update(transform)

    override fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a start from the background (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "Tracking service not started", e)
        }
    }

    override fun stop() {
        context.stopService(Intent(context, TrackingService::class.java))
    }

    private companion object {
        const val TAG = "TrackingController"
    }
}
