package org.privatetracker.feature.tracker.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.core.domain.usecase.tracker.FailOverServerAddress
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.feature.tracker.service.ServiceTrackingController
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for uploads, used by the tracking service and by [UploadWorker].
 * One upload runs at a time, so the outbox is never sent twice in parallel.
 */
@Singleton
class UploadCoordinator @Inject constructor(
    private val upload: UploadPendingLocations,
    private val failOver: FailOverServerAddress,
    private val controller: ServiceTrackingController,
    private val scheduler: UploadScheduler,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    // Outlives the service, so an upload in progress finishes after tracking stops.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Drains the outbox now, waiting for an upload already in progress. When the server cannot be
     * reached at its address, or someone else answers there, tries the other addresses of its QR once.
     */
    suspend fun uploadNow(): UploadResult = mutex.withLock {
        var result = drain()
        if (result.lostTheServer() && failOver()) result = drain()
        result
    }

    private suspend fun drain(): UploadResult {
        var result: UploadResult
        do {
            result = upload()
            val finished = result
            controller.update { it.copy(lastUpload = finished, lastUploadAt = clock.now()) }
        } while (result is UploadResult.Completed && result.hasMore)
        return result
    }

    private fun UploadResult.lostTheServer(): Boolean =
        this is UploadResult.RetryLater && (error is DomainError.Network || error == DomainError.ServerIdentityMismatch)

    /**
     * Fire and forget, after each new fix. Skipped when an upload is running: that one drains the
     * outbox. A transient failure hands the retry to WorkManager, which applies backoff.
     */
    fun requestUpload() {
        if (mutex.isLocked) return
        scope.launch {
            val result = uploadNow()
            if (result is UploadResult.RetryLater) {
                scheduler.scheduleUpload(Duration.ofSeconds(result.retryAfterSeconds ?: WorkManagerUploadScheduler.BACKOFF_SECONDS))
            }
        }
    }
}
