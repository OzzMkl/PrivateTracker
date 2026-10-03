package org.privatetracker.core.domain.repository

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PairingTicket
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionId
import java.time.Instant

// Server-side storage. Implementations throw only on unexpected storage failures.

interface DeviceRepository {
    suspend fun get(id: DeviceId): Device?
    suspend fun getWithLastLocation(id: DeviceId): DeviceWithLastLocation?
    suspend fun getAllWithLastLocation(): List<DeviceWithLastLocation>
    fun observeAllWithLastLocation(): Flow<List<DeviceWithLastLocation>>
    suspend fun insert(device: Device)
    suspend fun update(device: Device)
    suspend fun setLastLocation(id: DeviceId, locationId: LocationId)

    /** Deletes the device together with its locations and sessions. Returns false if it did not exist. */
    suspend fun delete(id: DeviceId): Boolean
}

interface LocationRepository {
    /** Stores [location] unless one with the same device and id exists. Returns true when it was inserted. */
    suspend fun insertIfAbsent(location: Location, sessionId: SessionId?): Boolean

    /** Deletes locations received before [cutoff], except the last known location of each device. */
    suspend fun deleteReceivedBefore(cutoff: Instant): Int
}

interface SessionRepository {
    suspend fun findOpen(deviceId: DeviceId): DeviceSession?
    suspend fun findAllOpen(): List<DeviceSession>

    /** Open sessions whose last activity is before [cutoff]. */
    suspend fun findOpenInactiveSince(cutoff: Instant): List<DeviceSession>
    suspend fun findRecent(deviceId: DeviceId, limit: Int): List<DeviceSession>
    suspend fun insert(session: DeviceSession)
    suspend fun update(session: DeviceSession)
}

interface ServerConfigRepository {
    fun observe(): Flow<ServerConfig>
    suspend fun get(): ServerConfig
    suspend fun update(transform: (ServerConfig) -> ServerConfig): ServerConfig
}

/**
 * Tickets of QR invites. They live in memory: a restart of the server only means showing a new QR.
 * One ticket is open at a time; issuing a new one withdraws any ticket not yet used.
 */
interface PairingTicketStore {
    fun issue(ticket: PairingTicket)
    fun get(id: String): PairingTicket?

    /**
     * Marks [id] as used by [deviceId], atomically. True also when [deviceId] already used it, so a
     * tracker that lost the answer can register again; false when another device took it first.
     */
    fun consume(id: String, deviceId: DeviceId): Boolean

    /** Ends [id] if it is still the open ticket, as when its QR leaves the screen. */
    fun withdraw(id: String)
    fun observe(id: String): Flow<PairingTicket?>
}
