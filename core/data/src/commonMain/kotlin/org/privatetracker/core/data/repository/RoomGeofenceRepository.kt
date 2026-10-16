package org.privatetracker.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.privatetracker.core.data.mapper.millis
import org.privatetracker.core.data.mapper.toInstant
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.server.DeviceDao
import org.privatetracker.core.database.server.GeofenceDao
import org.privatetracker.core.database.server.GeofenceDeviceEntity
import org.privatetracker.core.database.server.GeofenceDeviceRow
import org.privatetracker.core.database.server.GeofenceEntity
import org.privatetracker.core.database.server.GeofenceEventEntity
import org.privatetracker.core.database.server.GeofenceStateEntity
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEvent
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.GeofenceState
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.repository.GeofenceRepository
import java.time.Instant

class RoomGeofenceRepository(
    private val database: ServerDatabase,
    private val dao: GeofenceDao,
    private val devices: DeviceDao,
) : GeofenceRepository {
    override fun observeAll(): Flow<List<Geofence>> = dao.observeAll().map { it.toGeofences() }

    override suspend fun all(): List<Geofence> = dao.all().toGeofences()

    override suspend fun get(id: GeofenceId): Geofence? = dao.get(id.value).toGeofences().firstOrNull()

    override suspend fun save(geofence: Geofence, keepStates: Boolean) = database.inTransaction {
        val shape = geofence.shape
        val circle = shape as? GeofenceShape.Circle
        val vertices = (shape as? GeofenceShape.Polygon)?.vertices?.joinToString(";") { "${it.latitude},${it.longitude}" }
        val kind = if (circle != null) CIRCLE else POLYGON
        val key = dao.internalId(geofence.id.value)?.also { id ->
            dao.update(
                id, geofence.name, kind, circle?.center?.latitude, circle?.center?.longitude, circle?.radiusM, vertices,
                geofence.deviceIds == null, geofence.notifyOnEnter, geofence.notifyOnExit,
            )
            dao.clearDevices(id)
            if (!keepStates) dao.clearStates(id)
        } ?: dao.insert(
            GeofenceEntity(
                geofenceUid = geofence.id.value,
                name = geofence.name,
                kind = kind,
                centerLatitude = circle?.center?.latitude,
                centerLongitude = circle?.center?.longitude,
                radiusM = circle?.radiusM,
                vertices = vertices,
                allDevices = geofence.deviceIds == null,
                notifyEnter = geofence.notifyOnEnter,
                notifyExit = geofence.notifyOnExit,
                createdAt = geofence.createdAt.millis(),
            ),
        )
        val chosen = geofence.deviceIds.orEmpty().mapNotNull { devices.internalId(it.value) }
        dao.insertDevices(chosen.map { GeofenceDeviceEntity(key, it) })
        if (geofence.deviceIds != null) dao.clearUnwatchedStates(key)
    }

    override suspend fun delete(id: GeofenceId): Boolean = dao.delete(id.value) > 0

    override suspend fun state(geofenceId: GeofenceId, deviceId: DeviceId): GeofenceState? =
        dao.state(geofenceId.value, deviceId.value)?.let { row ->
            GeofenceState(
                geofenceId = GeofenceId.of(row.geofenceUid),
                deviceId = DeviceId.of(row.deviceUid),
                inside = row.state.inside,
                candidateInside = row.state.candidateInside,
                candidateCount = row.state.candidateCount,
                candidateSince = row.state.candidateSince?.toInstant(),
                lastRecordedAt = row.state.lastRecordedAt.toInstant(),
            )
        }

    override suspend fun saveState(state: GeofenceState) {
        val geofenceKey = dao.internalId(state.geofenceId.value) ?: return
        val deviceKey = devices.internalId(state.deviceId.value) ?: return
        dao.saveState(
            GeofenceStateEntity(
                geofenceKey, deviceKey, state.inside, state.candidateInside, state.candidateCount,
                state.candidateSince?.millis(), state.lastRecordedAt.millis(),
            ),
        )
    }

    override suspend fun addEvent(event: GeofenceEvent) {
        val geofenceKey = dao.internalId(event.geofenceId.value) ?: return
        val deviceKey = devices.internalId(event.deviceId.value) ?: return
        dao.insertEvent(
            GeofenceEventEntity(
                0, geofenceKey, deviceKey, event.transition.name, event.occurredAt.millis(), event.detectedAt.millis(), event.alerted,
            ),
        )
    }

    override fun observeEvents(limit: Int): Flow<List<GeofenceEventView>> = dao.observeEvents(limit).map { rows ->
        rows.map { row ->
            GeofenceEventView(
                event = GeofenceEvent(
                    geofenceId = GeofenceId.of(row.geofenceUid),
                    deviceId = DeviceId.of(row.deviceUid),
                    transition = GeofenceTransition.valueOf(row.event.transition),
                    occurredAt = row.event.occurredAt.toInstant(),
                    detectedAt = row.event.detectedAt.toInstant(),
                    alerted = row.event.alerted,
                ),
                geofenceName = row.geofenceName,
                deviceName = row.deviceName,
            )
        }
    }

    override suspend fun deleteEventsBefore(cutoff: Instant): Int = dao.deleteEventsBefore(cutoff.millis())

    /** One row per chosen device, or one for a geofence that watches them all. */
    private fun List<GeofenceDeviceRow>.toGeofences(): List<Geofence> =
        groupBy { it.geofence.id }.values.map { rows ->
            val entity = rows.first().geofence
            Geofence(
                id = GeofenceId.of(entity.geofenceUid),
                name = entity.name,
                shape = entity.shape(),
                deviceIds = if (entity.allDevices) null else rows.mapNotNull { row -> row.deviceUid?.let(DeviceId::of) }.toSet(),
                notifyOnEnter = entity.notifyEnter,
                notifyOnExit = entity.notifyExit,
                createdAt = entity.createdAt.toInstant(),
            )
        }

    private fun GeofenceEntity.shape(): GeofenceShape =
        if (kind == CIRCLE) {
            GeofenceShape.Circle(GeoPoint(checkNotNull(centerLatitude), checkNotNull(centerLongitude)), checkNotNull(radiusM))
        } else {
            GeofenceShape.Polygon(
                checkNotNull(vertices).split(";").map { pair ->
                    val (latitude, longitude) = pair.split(",").map(String::toDouble)
                    GeoPoint(latitude, longitude)
                },
            )
        }

    private companion object {
        const val CIRCLE = "CIRCLE"
        const val POLYGON = "POLYGON"
    }
}
