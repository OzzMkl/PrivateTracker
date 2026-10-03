package org.privatetracker.feature.tracker.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.permissionError
import org.privatetracker.core.domain.port.LocationRequestSpec
import org.privatetracker.core.domain.port.LocationSource
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.feature.tracker.worker.UploadCoordinator
import java.time.Duration
import javax.inject.Inject

/**
 * Collects fixes while tracking is on: each one goes to the outbox and triggers an upload. A change
 * of interval, distance or priority applies at once, without restarting the service.
 */
@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var locationSource: LocationSource
    @Inject lateinit var recordLocation: RecordLocation
    @Inject lateinit var uploader: UploadCoordinator
    @Inject lateinit var trackerConfig: TrackerConfigRepository
    @Inject lateinit var controller: ServiceTrackingController
    @Inject lateinit var uploadScheduler: UploadScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tracking: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                TrackingNotification.ID,
                TrackingNotification.waiting(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: SecurityException) {
            // Android 14+ refuses a location service without location permission.
            fail(e)
            return START_NOT_STICKY
        }
        if (tracking == null) tracking = scope.launch { track() }
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun track() {
        // A sticky restart after process death only resumes if the user still wants tracking.
        if (!trackerConfig.get().trackingEnabled) {
            stopSelf()
            return
        }
        controller.update { it.copy(running = true, error = null) }
        try {
            trackerConfig.observe()
                .map { it.toRequest() }
                .distinctUntilChanged()
                .collectLatest { request ->
                    locationSource.locations(request).collect { fix ->
                        val result = recordLocation(fix)
                        controller.update { it.copy(lastFix = fix, lastRecord = result) }
                        TrackingNotification.show(this, TrackingNotification.lastFix(this, fix.recordedAt))
                        uploader.requestUpload()
                    }
                }
        } catch (e: SecurityException) {
            fail(e)
        }
    }

    private fun TrackerConfig.toRequest() = LocationRequestSpec(intervalSeconds * 1_000L, minDistanceM, priority)

    private fun fail(cause: Throwable) {
        Log.e(TAG, "Location permission missing", cause)
        controller.update { it.copy(running = false, error = permissionError(listOf(AppPermission.PRECISE_LOCATION))) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        controller.update { it.copy(running = false) }
        // Whatever is still in the outbox goes out when the network allows, even after the app dies.
        uploadScheduler.scheduleUpload(Duration.ZERO)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "TrackingService"
    }
}
