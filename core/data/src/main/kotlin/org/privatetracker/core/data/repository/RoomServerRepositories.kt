package org.privatetracker.core.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.privatetracker.core.data.mapper.millis
import org.privatetracker.core.data.mapper.toDomain
import org.privatetracker.core.data.mapper.toEntity
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.server.DeviceDao
import org.privatetracker.core.database.server.DeviceSessionEntity
import org.privatetracker.core.database.server.LocationDao
import org.privatetracker.core.database.server.SessionDao
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.SessionId
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.SessionRepository
import java.time.Instant
import javax.inject.Inject

/** Repository calls made inside [run] join the same SQLite transaction. */
class RoomTransactionRunner @Inject constructor(private val database: ServerDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}

class RoomDeviceRepository @Inject constructor(private val dao: DeviceDao) : DeviceRepository {
    override suspend fun get(id: DeviceId): Device? = dao.get(id.value)?.toDomain()

    override suspend fun getWithLastLocation(id: DeviceId): DeviceWithLastLocation? =
        dao.getWithLastLocation(id.value)?.toDomain()

    override suspend fun getAllWithLastLocation(): List<DeviceWithLastLocation> =
        dao.getAllWithLastLocation().map { it.toDomain() }

    override fun observeAllWithLastLocation(): Flow<List<DeviceWithLastLocation>> =
        dao.observeAllWithLastLocation().map { rows -> rows.map { it.toDomain() } }

    override suspend fun insert(device: Device) {
        dao.insert(device.toEntity())
    }

    override suspend fun update(device: Device) {
        val updated = dao.updateDetails(
            uid = device.id.value,
            name = device.name,
            platform = device.platform.name,
            appVersion = device.appVersion,
            protocolVersion = device.protocolVersion,
            publicKey = device.publicKey,
            lastSeenAt = device.lastSeenAt?.millis(),
        )
        check(updated == 1) { "Device ${device.id} does not exist" }
    }

    override suspend fun setLastLocation(id: DeviceId, locationId: LocationId) {
        dao.setLastLocation(id.value, locationId.value)
    }

    override suspend fun delete(id: DeviceId): Boolean = dao.delete(id.value) > 0
}

class RoomLocationRepository @Inject constructor(
    private val locations: LocationDao,
    private val devices: DeviceDao,
    private val sessions: SessionDao,
) : LocationRepository {
    override suspend fun insertIfAbsent(location: Location, sessionId: SessionId?): Boolean {
        val deviceKey = checkNotNull(devices.internalId(location.deviceId.value)) { "Unknown device ${location.deviceId}" }
        val sessionKey = sessionId?.let { sessions.internalId(it.value) }
        return locations.insertIfAbsent(location.toEntity(deviceKey, sessionKey)) != -1L
    }

    override suspend fun deleteReceivedBefore(cutoff: Instant): Int = locations.deleteReceivedBefore(cutoff.millis())
}

class RoomSessionRepository @Inject constructor(
    private val sessions: SessionDao,
    private val devices: DeviceDao,
) : SessionRepository {
    override suspend fun findOpen(deviceId: DeviceId): DeviceSession? = sessions.findOpen(deviceId.value)?.toDomain()

    override suspend fun findAllOpen(): List<DeviceSession> = sessions.findAllOpen().map { it.toDomain() }

    override suspend fun findOpenInactiveSince(cutoff: Instant): List<DeviceSession> =
        sessions.findOpenInactiveSince(cutoff.millis()).map { it.toDomain() }

    override suspend fun findRecent(deviceId: DeviceId, limit: Int): List<DeviceSession> =
        sessions.findRecent(deviceId.value, limit).map { it.toDomain() }

    override suspend fun insert(session: DeviceSession) {
        val deviceKey = checkNotNull(devices.internalId(session.deviceId.value)) { "Unknown device ${session.deviceId}" }
        sessions.insert(
            DeviceSessionEntity(
                sessionUid = session.id.value,
                deviceId = deviceKey,
                startedAt = session.startedAt.millis(),
                lastActivityAt = session.lastActivityAt.millis(),
                endedAt = session.endedAt?.millis(),
                endReason = session.endReason?.name,
                remoteAddress = session.remoteAddress,
                appVersion = session.appVersion,
                locationsReceived = session.locationsReceived,
            ),
        )
    }

    override suspend fun update(session: DeviceSession) {
        val updated = sessions.update(
            uid = session.id.value,
            lastActivityAt = session.lastActivityAt.millis(),
            endedAt = session.endedAt?.millis(),
            endReason = session.endReason?.name,
            remoteAddress = session.remoteAddress,
            appVersion = session.appVersion,
            locationsReceived = session.locationsReceived,
        )
        check(updated == 1) { "Session ${session.id} does not exist" }
    }
}
