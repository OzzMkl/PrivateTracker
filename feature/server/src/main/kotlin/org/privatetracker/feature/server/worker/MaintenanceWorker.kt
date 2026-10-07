package org.privatetracker.feature.server.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.PurgeExpiredLocations
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the daily maintenance while the server is on. */
@Singleton
class MaintenanceScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "server-maintenance"
    }
}

/**
 * Daily: closes sessions of silent devices, applies the retention period, and rotates the encryption
 * key when due, which also deletes keys past their time even if no tracker asked for a key lately.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val closeInactiveSessions: CloseInactiveSessions,
    private val purgeExpiredLocations: PurgeExpiredLocations,
    private val encryptionKeys: EncryptionKeyRing,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val closed = closeInactiveSessions()
        val purged = purgeExpiredLocations()
        val key = try {
            encryptionKeys.current().key.id
        } catch (e: DeviceKeyException) {
            Log.e(TAG, "Encryption key unavailable", e)
            null
        }
        Log.i(TAG, "Closed $closed sessions, purged $purged locations, encryption key $key")
        return Result.success()
    }

    private companion object {
        const val TAG = "ServerMaintenance"
    }
}
