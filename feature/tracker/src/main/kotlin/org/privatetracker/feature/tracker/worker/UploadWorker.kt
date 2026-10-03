package org.privatetracker.feature.tracker.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.port.UploadScheduler
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads that must survive process death and reboots. While the tracking service runs it uploads in
 * process; WorkManager only retries failures and drains the outbox after tracking stops, because
 * since Android 16 jobs running alongside a foreground service count against the job quota.
 */
@Singleton
class WorkManagerUploadScheduler @Inject constructor(@ApplicationContext private val context: Context) : UploadScheduler {
    /** A pending upload is kept as is (KEEP): its backoff already accounts for earlier failures. */
    override fun scheduleUpload(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delay)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "tracker-upload"
        const val BACKOFF_SECONDS = 30L
    }
}

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UploadCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (coordinator.uploadNow()) {
        is UploadResult.Completed -> Result.success()
        is UploadResult.RetryLater -> Result.retry()
        // Retrying cannot fix a bad configuration or an incompatible server; the screen shows the error.
        is UploadResult.Blocked -> Result.failure()
    }
}
