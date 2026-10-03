package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity

/**
 * Sends the outbox to the server in batches, oldest first. A location leaves the outbox only when the
 * server answers for it (accepted, duplicate or rejected); on any failure the outbox stays intact.
 * Callers must not run two uploads at once.
 */
class UploadPendingLocations(
    private val outbox: OutboxRepository,
    private val gateway: ServerGateway,
    private val trackerConfig: TrackerConfigRepository,
    private val trackerState: TrackerStateRepository,
    private val registerDevice: RegisterDevice,
    private val identity: GetOrCreateDeviceIdentity,
    private val clock: Clock,
) {
    suspend operator fun invoke(maxBatches: Int = DEFAULT_MAX_BATCHES): UploadResult {
        val config = trackerConfig.get()
        if (config.serverUrl.isBlank()) return UploadResult.Blocked(DomainError.NotConfigured)
        if (outbox.count() == 0) return UploadResult.Completed(sent = 0, rejected = 0, hasMore = false)

        val deviceId = identity()
        var registration = when (val result = ensureRegistered(config.serverUrl)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return failure(result.error, attempted = emptyList())
        }
        var batchSize = minOf(config.batchSize, registration.maxBatchSize).coerceAtLeast(1)
        var sent = 0
        var rejected = 0
        var reRegistered = false
        var batches = 0

        while (batches < maxBatches) {
            val pending = outbox.peek(batchSize)
            if (pending.isEmpty()) return UploadResult.Completed(sent, rejected, hasMore = false)
            val attempted = pending.map { it.location.id }

            when (val result = gateway.uploadLocations(config.serverUrl, deviceId, pending.map { it.location })) {
                is Outcome.Success -> {
                    val acknowledged = result.value.acknowledgedIds()
                    if (acknowledged.isEmpty()) {
                        return failure(DomainError.Network.InvalidResponse("server acknowledged no location"), attempted)
                    }
                    outbox.remove(attempted.filter { it.value in acknowledged })
                    sent += result.value.accepted.size + result.value.duplicates.size
                    rejected += result.value.rejected.size
                    batches++
                }

                is Outcome.Failure -> when (val error = result.error) {
                    // The server lost or never had our registration: register again, once per run.
                    DomainError.DeviceNotRegistered -> {
                        if (reRegistered) return failure(error, attempted)
                        reRegistered = true
                        trackerState.setRegistration(null)
                        registration = when (val again = ensureRegistered(config.serverUrl)) {
                            is Outcome.Success -> again.value
                            is Outcome.Failure -> return failure(again.error, attempted)
                        }
                        batchSize = minOf(batchSize, registration.maxBatchSize).coerceAtLeast(1)
                    }

                    is DomainError.BatchTooLarge -> {
                        if (batchSize == 1) return failure(error, attempted)
                        batchSize = (batchSize / 2).coerceAtLeast(1)
                    }

                    else -> return failure(error, attempted)
                }
            }
        }
        return UploadResult.Completed(sent, rejected, hasMore = outbox.count() > 0)
    }

    private suspend fun ensureRegistered(serverUrl: String): Outcome<TrackerRegistration> {
        val known = trackerState.registration()
        return if (known != null && known.serverUrl == serverUrl) Outcome.Success(known) else registerDevice()
    }

    private suspend fun failure(error: DomainError, attempted: List<LocationId>): UploadResult {
        if (attempted.isNotEmpty()) outbox.markAttempt(attempted, clock.now(), error.code)
        return if (error.isTransient()) {
            UploadResult.RetryLater(error, (error as? DomainError.Http)?.retryAfterSeconds)
        } else {
            UploadResult.Blocked(error)
        }
    }

    private fun DomainError.isTransient(): Boolean = when (this) {
        is DomainError.Network -> true
        is DomainError.Http -> status == 429 || status >= 500
        else -> false
    }

    companion object {
        const val DEFAULT_MAX_BATCHES = 20
    }
}
