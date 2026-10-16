package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.geo.insideDepthM
import org.privatetracker.core.domain.geo.isSelfIntersecting
import org.privatetracker.core.domain.geo.signedDistanceToEdge
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEvent
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceRules
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.GeofenceState
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.core.domain.repository.GeofenceRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import java.time.Duration
import java.time.Instant

/** Which side of a geofence's edge one position puts a device on, with GPS noise in mind. */
enum class Side { INSIDE, OUTSIDE, UNSURE }

/**
 * A crossing a run of positions confirmed: the side reached, when the first of them was recorded
 * ([occurredAt], what the owner is told) and when the last one, which confirmed it ([confirmedAt]).
 */
data class Crossing(val transition: GeofenceTransition, val occurredAt: Instant, val confirmedAt: Instant)

/**
 * Crossing detection with hysteresis, from 0.7 on. A position counts as inside or outside only when
 * it lies past the edge by a margin of 1.5 times its accuracy (15 to 75 m, and at most half the area's
 * [insideDepthM]); closer than that it is unsure. The side changes only after [GeofenceRules.CONFIRMATIONS]
 * positions in a row agree, with no unsure one between them, so a phone standing at the edge, whose
 * fixes wander across it, does not flap. The first side a device is ever seen on raises no event.
 */
object CrossingDetector {
    /** [depthM] is the shape's [insideDepthM], for callers that look at many positions to work out once. */
    fun sideOf(shape: GeofenceShape, location: Location, depthM: Double = insideDepthM(shape)): Side {
        val accuracy = location.accuracyM?.toDouble() ?: GeofenceRules.ASSUMED_ACCURACY_M
        if (accuracy > GeofenceRules.MAX_ACCURACY_M) return Side.UNSURE
        val margin = (accuracy * GeofenceRules.MARGIN_PER_ACCURACY).coerceIn(GeofenceRules.MIN_MARGIN_M, GeofenceRules.MAX_MARGIN_M)
            .coerceAtMost(depthM / 2)
        val distance = signedDistanceToEdge(shape, GeoPoint(location.latitude, location.longitude))
        return when {
            distance >= margin -> Side.INSIDE
            distance <= -margin -> Side.OUTSIDE
            else -> Side.UNSURE
        }
    }

    /**
     * The state after [location], and the crossing it confirmed if any. [state] null means nothing is
     * known yet. A position not newer than the last one looked at changes nothing: batches can
     * arrive late, and history is not replayed.
     */
    fun step(
        state: GeofenceState?,
        geofence: Geofence,
        deviceId: DeviceId,
        location: Location,
        depthM: Double = insideDepthM(geofence.shape),
    ): Pair<GeofenceState, Crossing?> {
        val at = location.recordedAt
        if (state != null && !at.isAfter(state.lastRecordedAt)) return state to null
        val known = state ?: GeofenceState(geofence.id, deviceId, null, null, 0, null, at)
        val side = sideOf(geofence.shape, location, depthM)
        // An unsure position breaks a run: the confirmations must be consecutive positions.
        if (side == Side.UNSURE) return known.copy(candidateInside = null, candidateCount = 0, candidateSince = null, lastRecordedAt = at) to null
        val inside = side == Side.INSIDE
        if (known.inside == inside) return known.copy(candidateInside = null, candidateCount = 0, candidateSince = null, lastRecordedAt = at) to null

        val continues = known.candidateInside == inside
        val count = if (continues) known.candidateCount + 1 else 1
        val since = if (continues) known.candidateSince ?: at else at
        if (count < GeofenceRules.CONFIRMATIONS) {
            return known.copy(candidateInside = inside, candidateCount = count, candidateSince = since, lastRecordedAt = at) to null
        }
        val settled = GeofenceState(geofence.id, deviceId, inside, null, 0, null, at)
        val crossing = known.inside?.let { Crossing(if (inside) GeofenceTransition.ENTER else GeofenceTransition.EXIT, since, at) }
        return settled to crossing
    }
}

/**
 * Runs crossing detection over the positions a device just delivered, inside the ingest transaction,
 * and stores the events it confirms. Events confirmed too long before they arrive, of a kind the
 * geofence does not notify, or found while notifications are blocked are stored as not alerted.
 */
class EvaluateGeofences(
    private val geofences: GeofenceRepository,
    private val alerts: GeofenceAlerts,
    private val clock: Clock,
) {
    suspend operator fun invoke(device: Device, accepted: List<Location>): List<GeofenceEventView> {
        if (accepted.isEmpty()) return emptyList()
        val watching = geofences.all().filter { it.watches(device.id) }
        if (watching.isEmpty()) return emptyList()
        val ordered = accepted.sortedBy { it.recordedAt }
        val now = clock.now()
        // Asked only once something crossed.
        val audible by lazy { alerts.canAlert() }
        val found = mutableListOf<GeofenceEventView>()
        for (geofence in watching) {
            val depth = insideDepthM(geofence.shape)
            val before = geofences.state(geofence.id, device.id)
            var state = before
            for (location in ordered) {
                val (next, crossing) = CrossingDetector.step(state, geofence, device.id, location, depth)
                state = next
                if (crossing != null) {
                    // The window counts from the confirming position: a slow tracker takes minutes to confirm.
                    val fresh = Duration.between(crossing.confirmedAt, now) <= GeofenceRules.ALERT_WINDOW
                    val alerted = geofence.wants(crossing) && fresh && audible
                    val event = GeofenceEvent(geofence.id, device.id, crossing.transition, crossing.occurredAt, now, alerted)
                    geofences.addEvent(event)
                    found += GeofenceEventView(event, geofence.name, device.name)
                }
            }
            if (state != null && state != before) geofences.saveState(state)
        }
        return found.sortedBy { it.event.occurredAt }
    }

    private fun Geofence.wants(crossing: Crossing): Boolean =
        if (crossing.transition == GeofenceTransition.ENTER) notifyOnEnter else notifyOnExit
}

/** What the owner draws or changes; [id] null for a new geofence. */
data class GeofenceDraft(
    val id: GeofenceId?,
    val name: String,
    val shape: GeofenceShape,
    val deviceIds: Set<DeviceId>?,
    val notifyOnEnter: Boolean,
    val notifyOnExit: Boolean,
)

class SaveGeofence(
    private val geofences: GeofenceRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: GeofenceDraft): Outcome<Geofence> {
        val violations = validateGeofence(draft)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        val existing = draft.id?.let { geofences.get(it) }
        if (draft.id != null && existing == null) return DomainError.GeofenceNotFound.asFailure()
        val geofence = Geofence(
            id = draft.id ?: GeofenceId.of(ids.newId()),
            name = draft.name.trim(),
            shape = draft.shape,
            deviceIds = draft.deviceIds,
            notifyOnEnter = draft.notifyOnEnter,
            notifyOnExit = draft.notifyOnExit,
            createdAt = existing?.createdAt ?: clock.now(),
        )
        // A new name, other alerts or other devices leave the edge where it was: what is known stays.
        geofences.save(geofence, keepStates = existing?.shape == geofence.shape)
        return geofence.asSuccess()
    }
}

/** Every rule a geofence breaks; empty when it can be saved. */
fun validateGeofence(draft: GeofenceDraft): List<FieldViolation> = buildList {
    when {
        draft.name.isBlank() -> add(FieldViolation("name", FieldViolation.REQUIRED))
        draft.name.trim().length > Geofence.NAME_MAX_LENGTH -> add(FieldViolation("name", FieldViolation.TOO_LONG))
    }
    when (val shape = draft.shape) {
        is GeofenceShape.Circle -> {
            if (!shape.center.isValid()) add(FieldViolation("center", FieldViolation.INVALID_FORMAT))
            if (shape.radiusM !in Geofence.MIN_RADIUS_M..Geofence.MAX_RADIUS_M) add(FieldViolation("radius", FieldViolation.OUT_OF_RANGE))
        }
        is GeofenceShape.Polygon -> when {
            shape.vertices.size !in 3..Geofence.MAX_VERTICES -> add(FieldViolation("vertices", FieldViolation.OUT_OF_RANGE))
            shape.vertices.any { !it.isValid() } || isSelfIntersecting(shape.vertices) ->
                add(FieldViolation("vertices", FieldViolation.INVALID_FORMAT))
            // It must hold the smallest circle allowed: in a thinner one no position would count as inside.
            insideDepthM(shape) < Geofence.MIN_RADIUS_M -> add(FieldViolation("vertices", FieldViolation.OUT_OF_RANGE))
        }
    }
    if (draft.deviceIds?.isEmpty() == true) add(FieldViolation("devices", FieldViolation.REQUIRED))
}

private fun GeoPoint.isValid(): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0 && latitude.isFinite() && longitude.isFinite()

class DeleteGeofence(private val geofences: GeofenceRepository) {
    suspend operator fun invoke(id: GeofenceId): Boolean = geofences.delete(id)
}

class ObserveGeofences(private val geofences: GeofenceRepository) {
    operator fun invoke(): Flow<List<Geofence>> = geofences.observeAll()
}

class GetGeofence(private val geofences: GeofenceRepository) {
    suspend operator fun invoke(id: GeofenceId): Geofence? = geofences.get(id)
}

class ObserveGeofenceEvents(private val geofences: GeofenceRepository) {
    operator fun invoke(limit: Int = DEFAULT_LIMIT): Flow<List<GeofenceEventView>> = geofences.observeEvents(limit)

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/** Events are kept as long as the positions they came from, counted the same way: from when they reached the server. */
class PurgeGeofenceEvents(
    private val geofences: GeofenceRepository,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int = geofences.deleteEventsBefore(clock.now().minus(serverConfig.get().retention))
}
