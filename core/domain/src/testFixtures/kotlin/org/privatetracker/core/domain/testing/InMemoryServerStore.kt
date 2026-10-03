package org.privatetracker.core.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionId
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import java.time.Instant

/**
 * The three server tables in memory, sharing state so deletes cascade like in the database.
 * Every access holds one lock, so the spike app can serve concurrent requests while the UI reads.
 */
class InMemoryServerStore : DeviceRepository, LocationRepository, SessionRepository {
    private val lock = Any()
    private val devices = linkedMapOf<DeviceId, Device>()
    private val lastLocationIds = mutableMapOf<DeviceId, LocationId>()
    private val locations = linkedMapOf<Pair<DeviceId, LocationId>, StoredLocation>()
    private val sessions = linkedMapOf<SessionId, DeviceSession>()
    private val version = MutableStateFlow(0)

    data class StoredLocation(val location: Location, val sessionId: SessionId?)

    val storedLocations: List<StoredLocation> get() = locked { locations.values.toList() }
    val storedSessions: List<DeviceSession> get() = locked { sessions.values.toList() }

    private inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    private fun changed() = version.update { it + 1 }

    // DeviceRepository

    override suspend fun get(id: DeviceId): Device? = locked { devices[id] }

    override suspend fun getWithLastLocation(id: DeviceId): DeviceWithLastLocation? = locked {
        devices[id]?.let { DeviceWithLastLocation(it, lastLocationOf(id)) }
    }

    override suspend fun getAllWithLastLocation(): List<DeviceWithLastLocation> = locked {
        devices.values.map { DeviceWithLastLocation(it, lastLocationOf(it.id)) }
    }

    override fun observeAllWithLastLocation(): Flow<List<DeviceWithLastLocation>> =
        version.map { getAllWithLastLocation() }

    override suspend fun insert(device: Device) = locked {
        check(device.id !in devices) { "Device ${device.id} already exists" }
        devices[device.id] = device
        changed()
    }

    override suspend fun update(device: Device) = locked {
        check(device.id in devices) { "Device ${device.id} does not exist" }
        devices[device.id] = device
        changed()
    }

    override suspend fun setLastLocation(id: DeviceId, locationId: LocationId) = locked {
        lastLocationIds[id] = locationId
        changed()
    }

    override suspend fun delete(id: DeviceId): Boolean = locked {
        val removed = devices.remove(id) != null
        lastLocationIds.remove(id)
        locations.keys.removeAll { it.first == id }
        sessions.values.removeAll { it.deviceId == id }
        changed()
        removed
    }

    /** Caller holds the lock. */
    private fun lastLocationOf(id: DeviceId): Location? =
        lastLocationIds[id]?.let { locations[id to it]?.location }

    // LocationRepository

    override suspend fun insertIfAbsent(location: Location, sessionId: SessionId?): Boolean = locked {
        val key = location.deviceId to location.id
        if (key in locations) {
            false
        } else {
            locations[key] = StoredLocation(location, sessionId)
            changed()
            true
        }
    }

    override suspend fun deleteReceivedBefore(cutoff: Instant): Int = locked {
        val keep = lastLocationIds.map { (device, location) -> device to location }.toSet()
        val expired = locations.filter { (key, stored) ->
            key !in keep && stored.location.receivedAt?.isBefore(cutoff) == true
        }.keys.toList()
        expired.forEach(locations::remove)
        changed()
        expired.size
    }

    // SessionRepository

    override suspend fun findOpen(deviceId: DeviceId): DeviceSession? = locked {
        sessions.values.firstOrNull { it.deviceId == deviceId && it.isOpen }
    }

    override suspend fun findAllOpen(): List<DeviceSession> = locked { sessions.values.filter { it.isOpen } }

    override suspend fun findOpenInactiveSince(cutoff: Instant): List<DeviceSession> = locked {
        sessions.values.filter { it.isOpen && it.lastActivityAt.isBefore(cutoff) }
    }

    override suspend fun findRecent(deviceId: DeviceId, limit: Int): List<DeviceSession> = locked {
        sessions.values.filter { it.deviceId == deviceId }.sortedByDescending { it.startedAt }.take(limit)
    }

    override suspend fun insert(session: DeviceSession) = locked {
        check(session.id !in sessions) { "Session ${session.id} already exists" }
        sessions[session.id] = session
    }

    override suspend fun update(session: DeviceSession) = locked {
        check(session.id in sessions) { "Session ${session.id} does not exist" }
        sessions[session.id] = session
    }
}

class InMemoryServerConfigRepository(initial: ServerConfig = ServerConfig()) : ServerConfigRepository {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<ServerConfig> = state
    override suspend fun get(): ServerConfig = state.value
    override suspend fun update(transform: (ServerConfig) -> ServerConfig): ServerConfig {
        state.update(transform)
        return state.value
    }
}
