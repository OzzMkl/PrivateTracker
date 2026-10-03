package org.privatetracker.app.tracker

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.app.di.ApplicationScope
import org.privatetracker.app.notification.Notifications
import org.privatetracker.app.work.WorkScheduler
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.port.LocationRequestSpec
import org.privatetracker.core.domain.port.LocationSource
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class TrackerState(
    val running: Boolean = false,
    val lastFix: LocationFix? = null,
    val lastRecord: RecordResult? = null,
    val lastUpload: UploadResult? = null,
    val lastUploadAt: Instant? = null,
    val error: String? = null,
)

/** Observable state of the tracker, shared by the service, the uploader and the UI. */
@Singleton
class TrackerRuntime @Inject constructor() {
    private val _state = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    fun update(transform: (TrackerState) -> TrackerState) = _state.update(transform)
}

/**
 * The single entry point for uploads, used by the tracking service and by [org.privatetracker.app.work.UploadWorker].
 * One upload runs at a time, so the outbox is never sent twice in parallel.
 */
@Singleton
class UploadCoordinator @Inject constructor(
    private val upload: UploadPendingLocations,
    private val runtime: TrackerRuntime,
    private val workScheduler: WorkScheduler,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Drains the outbox now, waiting for an upload already in progress. */
    suspend fun uploadNow(): UploadResult = mutex.withLock {
        var result: UploadResult
        do {
            result = upload()
            val finished = result
            runtime.update { it.copy(lastUpload = finished, lastUploadAt = clock.now()) }
        } while (result is UploadResult.Completed && result.hasMore)
        result
    }

    /**
     * Fire and forget, after each new fix. Skipped when an upload is running: that one drains the
     * outbox. A transient failure hands the retry to WorkManager, which applies backoff.
     */
    fun requestUpload() {
        if (mutex.isLocked) return
        scope.launch {
            val result = uploadNow()
            if (result is UploadResult.RetryLater) {
                workScheduler.scheduleUpload(Duration.ofSeconds(result.retryAfterSeconds ?: WorkScheduler.UPLOAD_BACKOFF_SECONDS))
            }
        }
    }
}

@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var locationSource: LocationSource
    @Inject lateinit var recordLocation: RecordLocation
    @Inject lateinit var uploader: UploadCoordinator
    @Inject lateinit var trackerConfig: TrackerConfigRepository
    @Inject lateinit var runtime: TrackerRuntime
    @Inject lateinit var workScheduler: WorkScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tracking: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_TRACKING,
                Notifications.tracking(this, "Esperando la primera ubicación…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: SecurityException) {
            // Android 14+ refuses a location service without location permission.
            fail("Sin permiso de ubicación", e)
            return START_NOT_STICKY
        }
        if (tracking == null) tracking = scope.launch { track() }
        return START_STICKY
    }

    private suspend fun track() {
        val config = trackerConfig.get()
        // A sticky restart after process death only resumes if the user still wants tracking.
        if (!config.trackingEnabled) {
            stopSelf()
            return
        }
        runtime.update { it.copy(running = true, error = null) }
        val request = LocationRequestSpec(config.intervalSeconds * 1_000L, config.minDistanceM, config.priority)
        try {
            locationSource.locations(request).collect { fix ->
                val result = recordLocation(fix)
                runtime.update { it.copy(lastFix = fix, lastRecord = result) }
                Notifications.update(this, Notifications.ID_TRACKING, Notifications.tracking(this, "Última ubicación: ${fix.recordedAt}"))
                uploader.requestUpload()
            }
        } catch (e: SecurityException) {
            fail("Sin permiso de ubicación", e)
        }
    }

    private fun fail(message: String, cause: Throwable) {
        Log.e(TAG, message, cause)
        runtime.update { it.copy(running = false, error = message) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runtime.update { it.copy(running = false) }
        // Whatever is still in the outbox goes out when the network allows, even after the app dies.
        workScheduler.scheduleUpload()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "TrackingService"
    }
}
