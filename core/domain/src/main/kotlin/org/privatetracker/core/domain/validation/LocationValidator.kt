package org.privatetracker.core.domain.validation

import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.RejectionReason
import java.time.Duration

data class LocationViolation(val reason: RejectionReason, val detail: String)

/** Checks the physical plausibility of a location. Used by the server on ingest and by the tracker on capture. */
class LocationValidator(
    private val clock: Clock,
    private val maxClockSkew: Duration = DEFAULT_MAX_CLOCK_SKEW,
) {
    fun validate(location: Location): LocationViolation? {
        with(location) {
            if (!latitude.isFinite() || latitude !in -90.0..90.0) {
                return LocationViolation(RejectionReason.INVALID_COORDINATES, "latitude must be within [-90, 90]")
            }
            if (!longitude.isFinite() || longitude !in -180.0..180.0) {
                return LocationViolation(RejectionReason.INVALID_COORDINATES, "longitude must be within [-180, 180]")
            }
            accuracyM?.let {
                if (!it.isFinite() || it < 0f) {
                    return LocationViolation(RejectionReason.INVALID_ACCURACY, "accuracy_m must be >= 0")
                }
            }
            altitudeM?.let {
                if (!it.isFinite()) return LocationViolation(RejectionReason.INVALID_FIELD, "altitude_m must be finite")
            }
            speedMps?.let {
                if (!it.isFinite() || it < 0f) {
                    return LocationViolation(RejectionReason.INVALID_FIELD, "speed_mps must be >= 0")
                }
            }
            bearingDeg?.let {
                if (!it.isFinite() || it < 0f || it >= 360f) {
                    return LocationViolation(RejectionReason.INVALID_FIELD, "bearing_deg must be within [0, 360)")
                }
            }
            batteryPct?.let {
                if (it !in 0..100) {
                    return LocationViolation(RejectionReason.INVALID_FIELD, "battery_pct must be within [0, 100]")
                }
            }
            if (recordedAt.isAfter(clock.now().plus(maxClockSkew))) {
                return LocationViolation(
                    RejectionReason.TIMESTAMP_IN_FUTURE,
                    "recorded_at is more than ${maxClockSkew.toMinutes()} min in the future",
                )
            }
        }
        return null
    }

    companion object {
        val DEFAULT_MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}
