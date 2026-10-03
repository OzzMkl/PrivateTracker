package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.domain.geo.distanceMeters
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.SkipReason
import org.privatetracker.core.domain.port.BatteryLevelProvider
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.validation.LocationValidator

/**
 * Turns a platform fix into a location in the outbox. Once this returns [RecordResult.Recorded],
 * the location survives process death and reboots until the server acknowledges it.
 */
class RecordLocation(
    private val outbox: OutboxRepository,
    private val trackerState: TrackerStateRepository,
    private val trackerConfig: TrackerConfigRepository,
    private val identity: GetOrCreateDeviceIdentity,
    private val battery: BatteryLevelProvider,
    private val validator: LocationValidator,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(fix: LocationFix): RecordResult {
        val config = trackerConfig.get()
        if (fix.accuracyM != null && fix.accuracyM > config.maxAccuracyM) return RecordResult.Skipped(SkipReason.LOW_ACCURACY)

        val last = trackerState.lastRecorded()
        if (config.minDistanceM > 0f && last != null &&
            distanceMeters(last.latitude, last.longitude, fix.latitude, fix.longitude) < config.minDistanceM
        ) {
            return RecordResult.Skipped(SkipReason.TOO_CLOSE)
        }

        val location = Location(
            id = LocationId.of(ids.newId()),
            deviceId = identity(),
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyM = fix.accuracyM,
            altitudeM = fix.altitudeM,
            speedMps = fix.speedMps,
            bearingDeg = fix.bearingDeg,
            provider = fix.provider,
            batteryPct = battery.currentLevelPct(),
            isMock = fix.isMock,
            recordedAt = fix.recordedAt,
        )
        if (validator.validate(location) != null) return RecordResult.Skipped(SkipReason.INVALID)

        val dropped = outbox.enqueue(location, config.maxQueueSize)
        trackerState.setLastRecorded(location)
        return RecordResult.Recorded(location, dropped)
    }
}
