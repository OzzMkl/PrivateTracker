package org.privatetracker.core.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.repository.IdentityRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import java.time.Instant

class InMemoryOutboxRepository : OutboxRepository {
    private val queue = MutableStateFlow<List<PendingLocation>>(emptyList())

    val pending: List<PendingLocation> get() = queue.value

    override suspend fun enqueue(location: Location, maxSize: Int): Int {
        var dropped = 0
        queue.update { current ->
            val appended = current + PendingLocation(location)
            dropped = (appended.size - maxSize).coerceAtLeast(0)
            appended.drop(dropped)
        }
        return dropped
    }

    override suspend fun peek(limit: Int): List<PendingLocation> = queue.value.take(limit)

    override suspend fun remove(ids: Collection<LocationId>) {
        val set = ids.toSet()
        queue.update { current -> current.filterNot { it.location.id in set } }
    }

    override suspend fun markAttempt(ids: Collection<LocationId>, at: Instant, errorCode: String) {
        val set = ids.toSet()
        queue.update { current ->
            current.map {
                if (it.location.id in set) it.copy(attempts = it.attempts + 1, lastAttemptAt = at, lastError = errorCode) else it
            }
        }
    }

    override suspend fun count(): Int = queue.value.size

    override fun observeCount(): Flow<Int> = queue.map { it.size }
}

class InMemoryTrackerStateRepository : TrackerStateRepository {
    var lastRecorded: Location? = null
    var registration: TrackerRegistration? = null

    override suspend fun lastRecorded(): Location? = lastRecorded
    override suspend fun setLastRecorded(location: Location) {
        lastRecorded = location
    }

    override suspend fun registration(): TrackerRegistration? = registration
    override suspend fun setRegistration(registration: TrackerRegistration?) {
        this.registration = registration
    }
}

class InMemoryIdentityRepository(private var deviceId: DeviceId? = null) : IdentityRepository {
    override suspend fun getOrCreate(create: () -> DeviceId): DeviceId = deviceId ?: create().also { deviceId = it }
}

class InMemoryTrackerConfigRepository(initial: TrackerConfig = TrackerConfig()) : TrackerConfigRepository {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<TrackerConfig> = state
    override suspend fun get(): TrackerConfig = state.value
    override suspend fun update(transform: (TrackerConfig) -> TrackerConfig): TrackerConfig {
        state.update(transform)
        return state.value
    }
}
