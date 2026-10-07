package org.privatetracker.tools.simulator

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.port.ServerGateway
import kotlin.random.Random

/**
 * Writes the server's answer about every batch to the ledger and times each upload. It sits next to
 * the HTTP client, so it sees answers that [FaultInjectingGateway] later hides from the tracker.
 */
class RecordingGateway(
    private val delegate: ServerGateway,
    private val ledger: Ledger,
    private val stats: TrackerStats,
) : ServerGateway by delegate {

    override suspend fun uploadLocations(
        serverUrl: String,
        pin: ServerPin,
        deviceId: DeviceId,
        locations: List<Location>,
    ): Outcome<LocationBatchResult> {
        val started = System.nanoTime()
        val result = delegate.uploadLocations(serverUrl, pin, deviceId, locations)
        if (result is Outcome.Success) {
            stats.answered(result.value, latencyMs = (System.nanoTime() - started) / 1_000_000)
            ledger.answered(deviceId, result.value)
        }
        return result
    }
}

/**
 * Breaks uploads on purpose, to show that the outbox and the server's idempotency make up for a bad
 * network: a dropped request is sent again later, and a lost answer comes back as duplicates.
 */
class FaultInjectingGateway(
    private val delegate: ServerGateway,
    private val plan: FaultPlan,
    private val random: Random,
    private val stats: TrackerStats,
) : ServerGateway by delegate {
    @Volatile
    var enabled = true

    override suspend fun uploadLocations(
        serverUrl: String,
        pin: ServerPin,
        deviceId: DeviceId,
        locations: List<Location>,
    ): Outcome<LocationBatchResult> {
        if (!enabled) return delegate.uploadLocations(serverUrl, pin, deviceId, locations)
        if (chance(plan.dropRate)) {
            stats.injectedDrops.incrementAndGet()
            return DomainError.Network.Unreachable.asFailure()
        }
        val result = delegate.uploadLocations(serverUrl, pin, deviceId, locations)
        if (result is Outcome.Success && chance(plan.lostAckRate)) {
            stats.injectedLostAcks.incrementAndGet()
            return DomainError.Network.Timeout.asFailure()
        }
        return result
    }

    private fun chance(probability: Double): Boolean =
        probability > 0.0 && synchronized(random) { random.nextDouble() } < probability
}
