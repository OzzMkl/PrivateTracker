package org.privatetracker.tools.simulator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.model.TrustedEncryptionKey
import org.privatetracker.core.domain.repository.IdentityRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import java.time.Instant

// Tracker storage in memory. The simulator process lives for the whole run, so nothing needs to
// survive a restart; the ledger is what survives.

/** Same contract as the Room outbox: oldest first, and a full queue drops its oldest to make room. */
class MemoryOutbox(private val onDropped: (List<Location>) -> Unit) : OutboxRepository {
    private val queue = ArrayDeque<PendingLocation>()
    private val size = MutableStateFlow(0)

    override suspend fun enqueue(location: Location, maxSize: Int): Int {
        val dropped = synchronized(queue) {
            queue.addLast(PendingLocation(location))
            val excess = (queue.size - maxSize).coerceAtLeast(0)
            List(excess) { queue.removeFirst().location }.also { size.value = queue.size }
        }
        if (dropped.isNotEmpty()) onDropped(dropped)
        return dropped.size
    }

    override suspend fun peek(limit: Int): List<PendingLocation> = synchronized(queue) { queue.take(limit) }

    override suspend fun remove(ids: Collection<LocationId>) {
        val set = ids.toSet()
        synchronized(queue) {
            queue.removeAll { it.location.id in set }
            size.value = queue.size
        }
    }

    override suspend fun markAttempt(ids: Collection<LocationId>, at: Instant, errorCode: String) {
        val set = ids.toSet()
        synchronized(queue) {
            val iterator = queue.listIterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.location.id in set) {
                    iterator.set(pending.copy(attempts = pending.attempts + 1, lastAttemptAt = at, lastError = errorCode))
                }
            }
        }
    }

    override suspend fun count(): Int = size.value

    override fun observeCount(): StateFlow<Int> = size.asStateFlow()
}

class MemoryTrackerState : TrackerStateRepository {
    @Volatile private var lastRecorded: Location? = null
    @Volatile private var registration: TrackerRegistration? = null
    @Volatile private var encryptionKey: TrustedEncryptionKey? = null

    override suspend fun lastRecorded(): Location? = lastRecorded
    override suspend fun setLastRecorded(location: Location) {
        lastRecorded = location
    }

    override suspend fun registration(): TrackerRegistration? = registration
    override suspend fun setRegistration(registration: TrackerRegistration?) {
        this.registration = registration
    }

    override suspend fun encryptionKey(): TrustedEncryptionKey? = encryptionKey
    override suspend fun setEncryptionKey(key: TrustedEncryptionKey?) {
        encryptionKey = key
    }
}

/** Each simulated phone has its id from the start. */
class FixedIdentity(private val deviceId: DeviceId) : IdentityRepository {
    override suspend fun getOrCreate(create: () -> DeviceId): DeviceId = deviceId
}

class MemoryTrackerConfig(initial: TrackerConfig) : TrackerConfigRepository {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<TrackerConfig> = state
    override suspend fun get(): TrackerConfig = state.value
    override suspend fun update(transform: (TrackerConfig) -> TrackerConfig): TrackerConfig =
        synchronized(state) { transform(state.value).also { state.value = it } }
}
