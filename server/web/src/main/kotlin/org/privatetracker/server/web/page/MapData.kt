package org.privatetracker.server.web.page

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.privatetracker.core.domain.geo.outline
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.Track

// What the pages' scripts draw, as JSON. Coordinates go [longitude, latitude], as GeoJSON and MapLibre
// take them; times as epoch milliseconds, which the browser shows in its own zone.

internal val WebJson = Json { encodeDefaults = true }

@Serializable
data class MapDevice(
    val id: String,
    val name: String,
    val status: String,
    val position: List<Double>,
    val accuracyM: Float?,
    val recordedAt: Long,
    val lastSeenAt: Long?,
    val batteryPct: Int?,
)

/** Devices the map shows: approved ones with a position. */
fun mapDevices(overviews: List<DeviceOverview>): List<MapDevice> = overviews.mapNotNull { overview ->
    val device = overview.device
    val location = overview.lastLocation ?: return@mapNotNull null
    if (device.approval != DeviceApproval.APPROVED) return@mapNotNull null
    MapDevice(
        id = device.id.value,
        name = device.name,
        status = overview.status.name,
        position = listOf(location.longitude, location.latitude),
        accuracyM = location.accuracyM,
        recordedAt = location.recordedAt.toEpochMilli(),
        lastSeenAt = device.lastSeenAt?.toEpochMilli(),
        batteryPct = location.batteryPct,
    )
}

fun mapDevicesJson(overviews: List<DeviceOverview>): String = WebJson.encodeToString(ListSerializer(MapDevice.serializer()), mapDevices(overviews))

@Serializable
data class MapGeofence(
    val id: String,
    val name: String,
    /** The edge as drawn, closed: the first point again at the end. */
    val outline: List<List<Double>>,
)

fun mapGeofencesJson(geofences: List<Geofence>): String = WebJson.encodeToString(
    ListSerializer(MapGeofence.serializer()),
    geofences.map { geofence ->
        val edge = outline(geofence.shape).map { it.lonLat() }
        MapGeofence(geofence.id.value, geofence.name, edge + listOfNotNull(edge.firstOrNull()))
    },
)

/** The shape being edited, as the editor's script reads and writes it in its hidden fields. */
@Serializable
data class DraftShape(
    val kind: String,
    val center: List<Double>? = null,
    val radiusM: Double? = null,
    val vertices: List<List<Double>> = emptyList(),
)

fun draftShapeJson(shape: GeofenceShape?): String = WebJson.encodeToString(
    DraftShape.serializer(),
    when (shape) {
        null -> DraftShape(kind = "circle", radiusM = 200.0)
        is GeofenceShape.Circle -> DraftShape(kind = "circle", center = shape.center.lonLat(), radiusM = shape.radiusM)
        is GeofenceShape.Polygon -> DraftShape(kind = "polygon", vertices = shape.vertices.map { it.lonLat() })
    },
)

@Serializable
data class MapTrack(
    val segments: List<List<List<Double>>>,
    val start: List<Double>?,
    val end: List<Double>?,
)

fun mapTrackJson(track: Track): String {
    val segments = track.segments.map { segment -> segment.map { listOf(it.longitude, it.latitude) } }
    return WebJson.encodeToString(MapTrack.serializer(), MapTrack(segments, segments.firstOrNull()?.firstOrNull(), segments.lastOrNull()?.lastOrNull()))
}

@Serializable
data class LiveAlert(
    val device: String,
    val geofence: String,
    val transition: String,
    val occurredAt: Long,
)

fun liveAlertsJson(events: List<GeofenceEventView>): String = WebJson.encodeToString(
    ListSerializer(LiveAlert.serializer()),
    events.map { LiveAlert(it.deviceName, it.geofenceName, it.event.transition.name, it.event.occurredAt.toEpochMilli()) },
)

private fun GeoPoint.lonLat(): List<Double> = listOf(longitude, latitude)
