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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.permissionError
import org.privatetracker.core.domain.port.LocationSource
import org.privatetracker.core.domain.port.MotionSensor
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.StillnessDetector
import org.privatetracker.core.domain.usecase.tracker.locationRequest
import org.privatetracker.feature.tracker.worker.UploadCoordinator
import java.time.Duration
import javax.inject.Inject

/**
 * Collects fixes while tracking is on: each one goes to the outbox and triggers an upload. A change
 * of interval, distance or priority applies at once, without restarting the service. From 0.6, while
 * the phone lies still the fixes come at the slower still interval, and the motion sensor brings them
 * back to the normal rate as soon as the phone moves.
 */
@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var locationSource: LocationSource
    @Inject lateinit var recordLocation: RecordLocation
    @Inject lateinit var uploader: UploadCoordinator
    @Inject lateinit var trackerConfig: TrackerConfigRepository
    @Inject lateinit var controller: ServiceTrackingController
    @Inject lateinit var uploadScheduler: UploadScheduler
    @Inject lateinit var motionSensor: MotionSensor

    /** One thread: the stillness detector hears fixes and the motion sensor, never both at once. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
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
        controller.update { it.copy(running = true, error = null, still = false) }
        val stillness = StillnessDetector()
        try {
            coroutineScope {
                val config = trackerConfig.observe().stateIn(this)
                val resting = combine(config, stillness.still) { settings, still -> settings.adaptiveInterval && still }.distinctUntilChanged()
                // The sensor is armed only while resting, which is when a wake-up matters.
                launch { resting.collectLatest { armed -> if (armed) motionSensor.motions().collect { stillness.onMotion() } } }
                launch { resting.collect { still -> controller.update { it.copy(still = still) } } }
                combine(config, stillness.still) { settings, still -> settings.locationRequest(still) }
                    .distinctUntilChanged()
                    .collectLatest { request ->
                        locationSource.locations(request).collect { fix ->
                            // A new request cancels this collector: every fix that arrived is recorded in full first.
                            val result = withContext(NonCancellable) {
                                recordLocation(fix).also { result ->
                                    controller.update { it.copy(lastFix = fix, lastRecord = result) }
                                    uploader.requestUpload()
                                }
                            }
                            TrackingNotification.show(this@TrackingService, TrackingNotification.lastFix(this@TrackingService, fix.recordedAt))
                            // Last, since it may change the request and so cancel this very collector.
                            stillness.onFix(fix)
                        }
                    }
            }
        } catch (e: SecurityException) {
            fail(e)
        }
    }

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
