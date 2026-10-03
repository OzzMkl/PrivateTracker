package org.privatetracker.app.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.app.tracker.UploadCoordinator
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.core.domain.usecase.server.PurgeExpiredLocations
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deferred work that must survive process death and reboots. While the tracking service runs it
 * uploads in process; WorkManager only retries failures and drains the outbox after tracking stops,
 * because since Android 16 jobs running alongside a foreground service count against the job quota.
 */
@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** A pending upload is kept as is (KEEP): its backoff already accounts for earlier failures. */
    fun scheduleUpload(delay: Duration = Duration.ZERO) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delay)
            .build()
        workManager.enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleServerMaintenance() {
        val request = PeriodicWorkRequestBuilder<ServerMaintenanceWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(MAINTENANCE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelServerMaintenance() {
        workManager.cancelUniqueWork(MAINTENANCE_WORK)
    }

    companion object {
        const val UPLOAD_WORK = "tracker-upload"
        const val MAINTENANCE_WORK = "server-maintenance"
        const val UPLOAD_BACKOFF_SECONDS = 30L
    }
}

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UploadCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val result = coordinator.uploadNow()) {
        is UploadResult.Completed -> Result.success()
        is UploadResult.RetryLater -> Result.retry()
        // Retrying cannot fix a bad configuration or an incompatible server; the UI shows the error.
        is UploadResult.Blocked -> Result.failure()
    }
}

/** Daily: closes sessions of silent devices and applies the retention period. */
@HiltWorker
class ServerMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val closeInactiveSessions: CloseInactiveSessions,
    private val purgeExpiredLocations: PurgeExpiredLocations,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val closed = closeInactiveSessions()
        val purged = purgeExpiredLocations()
        Log.i(TAG, "Closed $closed sessions, purged $purged locations")
        return Result.success()
    }

    private companion object {
        const val TAG = "ServerMaintenance"
    }
}
