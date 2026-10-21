package org.privatetracker.core.domain.repository

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEvent
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceState
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PairingTicket
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.model.PeerTicket
import org.privatetracker.core.domain.model.RemovedDevice
import org.privatetracker.core.domain.model.SyncCursor
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionId
import org.privatetracker.core.domain.model.StoredEncryptionKey
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.TrackPoint
import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.WebSession
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

    /**
     * Deletes the device together with its locations and sessions, and remembers it was removed at
     * [at], so paired servers remove it too (1.0). Returns false if it did not exist.
     */
    suspend fun delete(id: DeviceId, at: Instant): Boolean
}

interface LocationRepository {
    /** Stores [location] unless one with the same device and id exists. Returns true when it was inserted. */
    suspend fun insertIfAbsent(location: Location, sessionId: SessionId?): Boolean

    /** Deletes locations received before [cutoff], except the last known location of each device. */
    suspend fun deleteReceivedBefore(cutoff: Instant): Int

    /** Where [deviceId] was during [range], by recording time, oldest first: only what drawing needs. */
    suspend fun findTrackPoints(deviceId: DeviceId, range: TimeRange): List<TrackPoint>

    /** Every field of the locations [deviceId] recorded during [range], oldest first, for export. */
    suspend fun findRecorded(deviceId: DeviceId, range: TimeRange): List<Location>
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
 * Geofences, from 0.7 on, with what crossing detection remembers per device and the events it found.
 * Deleting a geofence or a device takes its states and events with it.
 */
interface GeofenceRepository {
    fun observeAll(): Flow<List<Geofence>>
    suspend fun all(): List<Geofence>
    suspend fun get(id: GeofenceId): Geofence?

    /**
     * Inserts or replaces [geofence]. The states of devices it no longer watches go; the others stay
     * only with [keepStates], which an edge that moved rules out.
     */
    suspend fun save(geofence: Geofence, keepStates: Boolean)
    suspend fun delete(id: GeofenceId): Boolean

    suspend fun state(geofenceId: GeofenceId, deviceId: DeviceId): GeofenceState?
    suspend fun saveState(state: GeofenceState)

    suspend fun addEvent(event: GeofenceEvent)

    /** The newest events first, with the names to show. */
    fun observeEvents(limit: Int): Flow<List<GeofenceEventView>>

    /** Deletes the events detected before [cutoff]. */
    suspend fun deleteEventsBefore(cutoff: Instant): Int
}

/** The server's encryption keys, newest last. They outlive restarts: trackers may hold any key still kept. */
interface EncryptionKeyRepository {
    fun observe(): Flow<List<StoredEncryptionKey>>
    suspend fun get(): List<StoredEncryptionKey>

    /** Replaces the keys with [transform]'s result, atomically. */
    suspend fun update(transform: (List<StoredEncryptionKey>) -> List<StoredEncryptionKey>): List<StoredEncryptionKey>
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

/** Accounts of the web app (1.0), by their lower-case name. */
interface WebAccountRepository {
    suspend fun all(): List<WebAccount>
    suspend fun get(name: String): WebAccount?

    /** False when an account of that name exists already; nothing changes then. */
    suspend fun insert(account: WebAccount): Boolean

    /**
     * False when no account has that name any more, or, with [ifHash], when its password hash is no
     * longer that one: someone changed the password meanwhile, and their change stands.
     */
    suspend fun update(account: WebAccount, ifHash: String? = null): Boolean
    suspend fun delete(name: String): Boolean
}

/** Signed-in browsers of the web app (1.0), by the hash of their token. They outlive restarts. */
interface WebSessionRepository {
    suspend fun add(session: WebSession)
    suspend fun get(tokenHash: String): WebSession?
    suspend fun delete(tokenHash: String)

    /** Ends every session of [account]. Returns how many there were. */
    suspend fun deleteAll(account: String): Int

    /** Drops the sessions that ended before [now]. Returns how many. */
    suspend fun deleteExpired(now: Instant): Int
}

/** Servers this one is paired with (1.0), by their identity key. */
interface PeerRepository {
    fun observeAll(): Flow<List<Peer>>
    suspend fun all(): List<Peer>
    suspend fun get(publicKey: String): Peer?
    suspend fun getByLabel(label: String): Peer?

    /** Adds [peer], or for a key already paired takes its name and addresses, keeping how far the two got. */
    suspend fun save(peer: Peer)

    /** Replaces the peer with [transform]'s result. False when no peer has that key any more. */
    suspend fun update(publicKey: String, transform: (Peer) -> Peer): Boolean
    suspend fun delete(publicKey: String): Boolean
}

/**
 * Tickets of invites for other servers (1.0), in memory like the trackers' ones and apart from them:
 * inviting a server does not withdraw a tracker's QR. One is open at a time.
 */
interface PeerTicketStore {
    fun issue(ticket: PeerTicket)
    fun get(id: String): PeerTicket?

    /** Marks [id] as used by the server with [publicKey]. True also when that server used it already. */
    fun consume(id: String, publicKey: String): Boolean
    fun withdraw(id: String)
    fun observe(id: String): Flow<PeerTicket?>
}

/**
 * This server's own changes in the order it made them, and how it takes in a paired server's (1.0).
 * Meant to run inside the caller's transaction, where what it reads and writes stays consistent.
 */
interface SyncRepository {
    /**
     * Devices changed and devices removed after [after] in this server's numbering of device changes,
     * oldest first, at most [limit] of both together.
     */
    suspend fun deviceChangesAfter(after: Long, limit: Int): DeviceChanges

    /** This server's locations after [after] in its own numbering, oldest first, but those that came from [peer]. */
    suspend fun locationsAfter(after: Long, peer: String, limit: Int): LocationChanges

    /** How far this server has numbered its own locations and device changes, ever. */
    suspend fun numbering(): SyncCursor

    /** When [id] was removed, if it was, here or on a paired server. */
    suspend fun removal(id: DeviceId): RemovedDevice?

    /** Writes [device] as an exchange settled it: inserted, or every field replaced. */
    suspend fun putDevice(device: Device)

    /** Removes the device if present and remembers the removal, unless a later one is remembered already. */
    suspend fun remove(removal: RemovedDevice)

    /** Stores a location from [peer] unless the device already has it. True when it was new. */
    suspend fun insertLocation(location: Location, peer: String): Boolean
}

/** [upTo]: where the next call starts. [complete]: nothing was left out for [limit][SyncRepository.deviceChangesAfter]. */
data class DeviceChanges(val devices: List<Device>, val removed: List<RemovedDevice>, val upTo: Long, val complete: Boolean)

data class LocationChanges(val locations: List<Location>, val upTo: Long, val complete: Boolean)
