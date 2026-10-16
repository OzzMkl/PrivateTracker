package org.privatetracker.core.domain.model

import java.time.Duration
import java.time.Instant

/** A point on Earth, in WGS84 degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** The area a geofence covers, from 0.7 on. */
sealed interface GeofenceShape {
    data class Circle(val center: GeoPoint, val radiusM: Double) : GeofenceShape

    /** A simple polygon; the last vertex joins the first, it is not repeated. */
    data class Polygon(val vertices: List<GeoPoint>) : GeofenceShape
}

/**
 * An area the owner watches on the server, from 0.7 on: when one of its devices enters or leaves, the
 * server records an event and, if [notifyOnEnter] or [notifyOnExit] say so, notifies the owner.
 * [deviceIds] null means every device, present and future.
 */
data class Geofence(
    val id: GeofenceId,
    val name: String,
    val shape: GeofenceShape,
    val deviceIds: Set<DeviceId>?,
    val notifyOnEnter: Boolean = true,
    val notifyOnExit: Boolean = true,
    val createdAt: Instant,
) {
    fun watches(deviceId: DeviceId): Boolean = deviceIds == null || deviceId in deviceIds

    companion object {
        const val NAME_MAX_LENGTH = 64

        /** Smaller areas sit inside the noise of an ordinary fix and would only flap. A polygon must hold a circle this size. */
        const val MIN_RADIUS_M = 50.0
        const val MAX_RADIUS_M = 50_000.0
        const val MAX_VERTICES = 50
    }
}

enum class GeofenceTransition { ENTER, EXIT }

/**
 * A device crossed a geofence's edge, as the server confirmed it. [occurredAt] is when the device
 * recorded the first position on the new side, [detectedAt] when the server confirmed it: events are
 * purged by the latter, as positions are by when they arrived. [alerted] is whether the owner was
 * notified: the geofence asks for it, the confirming position arrived soon enough, and notifications
 * were not blocked on the server's phone.
 */
data class GeofenceEvent(
    val geofenceId: GeofenceId,
    val deviceId: DeviceId,
    val transition: GeofenceTransition,
    val occurredAt: Instant,
    val detectedAt: Instant,
    val alerted: Boolean,
)

/** An event with the names the owner reads, for the screens and the notification. */
data class GeofenceEventView(val event: GeofenceEvent, val geofenceName: String, val deviceName: String)

/**
 * What the server remembers about one device and one geofence: the side it was last confirmed on
 * ([inside], null until known), the side a run of positions is pointing to and how long that run is,
 * and the newest position looked at, so a late batch cannot rewind the state.
 */
data class GeofenceState(
    val geofenceId: GeofenceId,
    val deviceId: DeviceId,
    val inside: Boolean?,
    val candidateInside: Boolean?,
    val candidateCount: Int,
    val candidateSince: Instant?,
    val lastRecordedAt: Instant,
)

/**
 * How cautious crossing detection is; the field tests of 0.7 tune these. Android reports accuracy as
 * the radius holding the true position with 68 % confidence, so one axis has a standard deviation of
 * about accuracy / 1.5. A margin of 1.5 × accuracy is then about 2.3 deviations: a phone standing right
 * on the edge puts a fix past it about 1 % of the time, and three in a row about once in a million.
 */
object GeofenceRules {
    /** Consecutive positions on the new side before a crossing counts; one unsure position breaks the run. */
    const val CONFIRMATIONS = 3

    /** A position must be this many times its accuracy past the edge, between the two limits below. */
    const val MARGIN_PER_ACCURACY = 1.5
    const val MIN_MARGIN_M = 15.0
    const val MAX_MARGIN_M = 75.0

    /** Positions without an accuracy are taken to be this good. */
    const val ASSUMED_ACCURACY_M = 25.0

    /** Fixes worse than this say nothing about which side of an edge a phone is on. */
    const val MAX_ACCURACY_M = 100.0

    /** Crossings confirmed longer ago than this when they arrive are recorded without notifying: a backlog, not news. */
    val ALERT_WINDOW: Duration = Duration.ofMinutes(15)
}
