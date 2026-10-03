package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.RejectedLocation
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.validation.LocationValidator

/**
 * Stores a batch of locations from one device. Each location is accepted, reported as a duplicate
 * (already stored, so a resend is harmless) or rejected with a reason. All writes share one transaction.
 */
class IngestLocationBatch(
    private val devices: DeviceRepository,
    private val locations: LocationRepository,
    private val sessions: SessionRepository,
    private val serverConfig: ServerConfigRepository,
    private val sessionTracker: SessionTracker,
    private val validator: LocationValidator,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        deviceId: DeviceId,
        batch: List<Location>,
        remoteAddress: String?,
    ): Outcome<LocationBatchResult> {
        val config = serverConfig.get()
        if (batch.size > config.maxBatchSize) return DomainError.BatchTooLarge(config.maxBatchSize).asFailure()

        val now = clock.now()
        val rejected = mutableListOf<RejectedLocation>()
        val valid = batch.mapNotNull { location ->
            val violation = validator.validate(location)
            if (violation == null) {
                location.copy(deviceId = deviceId, receivedAt = now)
            } else {
                rejected += RejectedLocation(location.id.value, violation.reason, violation.detail)
                null
            }
        }

        return transaction.run<Outcome<LocationBatchResult>> {
            val current = devices.getWithLastLocation(deviceId)
                ?: return@run DomainError.DeviceNotRegistered.asFailure()
            val session = sessionTracker.recordActivity(
                deviceId = deviceId,
                now = now,
                inactivityLimit = config.onlineThreshold,
                remoteAddress = remoteAddress,
                appVersion = null,
            )

            val accepted = mutableListOf<Location>()
            val duplicates = mutableListOf<LocationId>()
            for (location in valid) {
                if (locations.insertIfAbsent(location, session.id)) accepted += location else duplicates += location.id
            }

            // Batches can arrive out of order after an outage: only a newer fix moves the last location.
            val newest = accepted.maxByOrNull { it.recordedAt }
            val previous = current.lastLocation
            if (newest != null && (previous == null || newest.recordedAt.isAfter(previous.recordedAt))) {
                devices.setLastLocation(deviceId, newest.id)
            }
            devices.update(current.device.copy(lastSeenAt = now))
            if (accepted.isNotEmpty()) {
                sessions.update(session.copy(locationsReceived = session.locationsReceived + accepted.size))
            }

            LocationBatchResult(
                accepted = accepted.map { it.id },
                duplicates = duplicates,
                rejected = rejected,
                serverTime = now,
            ).asSuccess()
        }
    }
}
