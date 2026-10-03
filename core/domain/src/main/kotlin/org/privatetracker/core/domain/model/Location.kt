package org.privatetracker.core.domain.model

import java.time.Instant

/** A position captured by a tracker. Units are SI; coordinates are WGS84 decimal degrees. */
data class Location(
    val id: LocationId,
    val deviceId: DeviceId,
    val latitude: Double,
    val longitude: Double,
    /** Radius of 68% confidence, as Android reports it. */
    val accuracyM: Float? = null,
    /** Above the WGS84 ellipsoid. */
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val batteryPct: Int? = null,
    val isMock: Boolean = false,
    /** Time of the fix on the tracker. */
    val recordedAt: Instant,
    /** Set by the server on arrival; null on the tracker. */
    val receivedAt: Instant? = null,
)

/** A fix as the platform reports it, before it gets an id and enters the outbox. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val isMock: Boolean = false,
    val recordedAt: Instant,
)

/** A location waiting in the tracker outbox. */
data class PendingLocation(
    val location: Location,
    val attempts: Int = 0,
    val lastAttemptAt: Instant? = null,
    /** [org.privatetracker.core.common.result.DomainError.code] of the last failure. */
    val lastError: String? = null,
)

enum class RejectionReason { INVALID_COORDINATES, INVALID_ACCURACY, TIMESTAMP_IN_FUTURE, INVALID_FIELD }

/** [id] is a raw string because a location can be rejected precisely for having a malformed id. */
data class RejectedLocation(val id: String, val reason: RejectionReason, val detail: String)

data class LocationBatchResult(
    val accepted: List<LocationId>,
    val duplicates: List<LocationId>,
    val rejected: List<RejectedLocation>,
    val serverTime: Instant,
) {
    /** Ids the server answered for in any way; the tracker can drop all of them from its outbox. */
    fun acknowledgedIds(): Set<String> =
        buildSet {
            accepted.forEach { add(it.value) }
            duplicates.forEach { add(it.value) }
            rejected.forEach { add(it.id) }
        }
}
