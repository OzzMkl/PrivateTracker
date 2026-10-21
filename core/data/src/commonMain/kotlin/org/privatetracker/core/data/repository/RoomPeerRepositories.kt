package org.privatetracker.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.privatetracker.core.data.mapper.millis
import org.privatetracker.core.data.mapper.toDomain
import org.privatetracker.core.data.mapper.toEntity
import org.privatetracker.core.data.mapper.toInstant
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.server.RemovedDeviceEntity
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.model.RemovedDevice
import org.privatetracker.core.domain.model.SyncCursor
import org.privatetracker.core.domain.repository.DeviceChanges
import org.privatetracker.core.domain.repository.LocationChanges
import org.privatetracker.core.domain.repository.PeerRepository
import org.privatetracker.core.domain.repository.SyncRepository

// Paired servers, from 1.0 on, in server.db: the peers and their cursors live next to the rows they
// count, so a batch and how far it got commit together.

class RoomPeerRepository(private val database: ServerDatabase) : PeerRepository {
    private val dao = database.sync()

    override fun observeAll(): Flow<List<Peer>> = dao.observePeers().map { rows -> rows.map { it.toDomain() } }

    override suspend fun all(): List<Peer> = dao.peers().map { it.toDomain() }

    override suspend fun get(publicKey: String): Peer? = dao.peer(publicKey)?.toDomain()

    override suspend fun getByLabel(label: String): Peer? = dao.peerByLabel(label)?.toDomain()

    override suspend fun save(peer: Peer) {
        database.inTransaction {
            val existing = dao.peer(peer.publicKey)
            if (existing == null) {
                dao.insertPeer(peer.toEntity())
            } else {
                dao.updatePeer(existing.copy(name = peer.name, addresses = peer.addresses.joinToString("\n")))
            }
        }
    }

    override suspend fun update(publicKey: String, transform: (Peer) -> Peer): Boolean = database.inTransaction {
        val existing = dao.peer(publicKey) ?: return@inTransaction false
        val changed = transform(existing.toDomain())
        check(changed.publicKey == publicKey) { "A peer keeps its key" }
        dao.updatePeer(changed.toEntity(existing.id)) == 1
    }

    override suspend fun delete(publicKey: String): Boolean = dao.deletePeer(publicKey) > 0
}

class RoomSyncRepository(private val database: ServerDatabase) : SyncRepository {
    private val dao = database.sync()
    private val devices = database.devices()

    override suspend fun deviceChangesAfter(after: Long, limit: Int): DeviceChanges {
        dao.startNumbering()
        val changed = dao.devicesChangedAfter(after, limit + 1).map { it.changeSeq to it.toDomain() }
        val removed = dao.removalsAfter(after, limit + 1)
            .map { it.changeSeq to RemovedDevice(DeviceId.of(it.deviceUid), it.removedAt.toInstant()) }
        val all = (changed + removed).sortedBy { it.first }
        val taken = all.take(limit)
        val complete = all.size <= limit
        // Seen everything up to the counter: what comes later gets a higher number.
        val upTo = if (complete) maxOf(after, dao.counter(DEVICE_CHANGES) ?: 0) else taken.last().first
        return DeviceChanges(
            devices = taken.mapNotNull { it.second as? Device },
            removed = taken.mapNotNull { it.second as? RemovedDevice },
            upTo = upTo,
            complete = complete,
        )
    }

    override suspend fun locationsAfter(after: Long, peer: String, limit: Int): LocationChanges {
        val peerKey = dao.peer(peer)?.id ?: NO_PEER
        val rows = dao.locationsAfter(after, peerKey, limit + 1)
        val taken = rows.take(limit)
        val complete = rows.size <= limit
        // The rows skipped for coming from that peer count as seen too.
        val upTo = if (complete) maxOf(after, dao.lastLocationId()) else taken.last().location.id
        return LocationChanges(taken.map { it.location.toDomain(it.deviceUid) }, upTo, complete)
    }

    override suspend fun numbering(): SyncCursor {
        dao.startNumbering()
        return SyncCursor(dao.locationSequence() ?: dao.lastLocationId(), dao.counter(DEVICE_CHANGES) ?: 0)
    }

    override suspend fun removal(id: DeviceId): RemovedDevice? =
        dao.removal(id.value)?.let { RemovedDevice(DeviceId.of(it.deviceUid), it.removedAt.toInstant()) }

    override suspend fun putDevice(device: Device) {
        database.inTransaction {
            val change = dao.nextDeviceChange()
            if (devices.updateDetails(device, change) == 0) devices.insert(device.toEntity(change))
        }
    }

    override suspend fun remove(removal: RemovedDevice) {
        database.inTransaction {
            devices.delete(removal.id.value)
            val known = dao.removal(removal.id.value)
            // Numbered anew only when it is news, so a removal stops bouncing between servers.
            if (known == null || known.removedAt < removal.removedAt.millis()) {
                dao.putRemoval(RemovedDeviceEntity(removal.id.value, removal.removedAt.millis(), dao.nextDeviceChange()))
            }
        }
    }

    override suspend fun insertLocation(location: Location, peer: String): Boolean {
        val deviceKey = devices.internalId(location.deviceId.value) ?: return false
        val peerKey = dao.peer(peer)?.id
        return database.locations().insertIfAbsent(location.toEntity(deviceKey, sessionKey = null, fromPeer = peerKey)) != -1L
    }

    private companion object {
        /** No row has it: every location counts as not from that peer. */
        const val NO_PEER = -1L
    }
}
