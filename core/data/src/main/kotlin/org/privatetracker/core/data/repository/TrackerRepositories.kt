package org.privatetracker.core.data.repository

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.privatetracker.core.data.mapper.millis
import org.privatetracker.core.data.mapper.toData
import org.privatetracker.core.data.mapper.toDomain
import org.privatetracker.core.data.mapper.toOutboxEntity
import org.privatetracker.core.data.mapper.toRecordedData
import org.privatetracker.core.database.tracker.OutboxDao
import org.privatetracker.core.datastore.AppModeData
import org.privatetracker.core.datastore.IdentityData
import org.privatetracker.core.datastore.ServerConfigData
import org.privatetracker.core.datastore.TrackerConfigData
import org.privatetracker.core.datastore.TrackerStateData
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.IdentityRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import java.time.Instant
import javax.inject.Inject

class RoomOutboxRepository @Inject constructor(private val dao: OutboxDao) : OutboxRepository {
    override suspend fun enqueue(location: Location, maxSize: Int): Int = dao.enqueue(location.toOutboxEntity(), maxSize)

    override suspend fun peek(limit: Int): List<PendingLocation> = dao.peek(limit).map { it.toDomain() }

    override suspend fun remove(ids: Collection<LocationId>) {
        if (ids.isNotEmpty()) dao.delete(ids.map { it.value })
    }

    override suspend fun markAttempt(ids: Collection<LocationId>, at: Instant, errorCode: String) {
        if (ids.isNotEmpty()) dao.markAttempt(ids.map { it.value }, at.millis(), errorCode)
    }

    override suspend fun count(): Int = dao.count()

    override fun observeCount(): Flow<Int> = dao.observeCount()
}

class DataStoreTrackerConfigRepository @Inject constructor(
    private val store: DataStore<TrackerConfigData>,
) : TrackerConfigRepository {
    override fun observe(): Flow<TrackerConfig> = store.data.map { it.toDomain() }
    override suspend fun get(): TrackerConfig = store.data.first().toDomain()
    override suspend fun update(transform: (TrackerConfig) -> TrackerConfig): TrackerConfig =
        store.updateData { transform(it.toDomain()).toData() }.toDomain()
}

class DataStoreServerConfigRepository @Inject constructor(
    private val store: DataStore<ServerConfigData>,
) : ServerConfigRepository {
    override fun observe(): Flow<ServerConfig> = store.data.map { it.toDomain() }
    override suspend fun get(): ServerConfig = store.data.first().toDomain()
    override suspend fun update(transform: (ServerConfig) -> ServerConfig): ServerConfig =
        store.updateData { transform(it.toDomain()).toData() }.toDomain()
}

/** updateData is atomic, so two first launches racing still end with a single id. */
class DataStoreIdentityRepository @Inject constructor(
    private val store: DataStore<IdentityData>,
) : IdentityRepository {
    override suspend fun getOrCreate(create: () -> DeviceId): DeviceId {
        val stored = store.updateData { current ->
            if (current.deviceId?.let(DeviceId::parse) != null) current else current.copy(deviceId = create().value)
        }
        return DeviceId.of(checkNotNull(stored.deviceId))
    }
}

class DataStoreTrackerStateRepository @Inject constructor(
    private val store: DataStore<TrackerStateData>,
) : TrackerStateRepository {
    override suspend fun lastRecorded(): Location? = store.data.first().lastRecorded?.toDomain()

    override suspend fun setLastRecorded(location: Location) {
        store.updateData { it.copy(lastRecorded = location.toRecordedData()) }
    }

    override suspend fun registration(): TrackerRegistration? = store.data.first().registration?.toDomain()

    override suspend fun setRegistration(registration: TrackerRegistration?) {
        store.updateData { it.copy(registration = registration?.toData()) }
    }
}

class DataStoreAppModeRepository @Inject constructor(
    private val store: DataStore<AppModeData>,
) : AppModeRepository {
    override fun observe(): Flow<AppMode?> = store.data.map { it.toDomain() }
    override suspend fun get(): AppMode? = store.data.first().toDomain()
    override suspend fun set(mode: AppMode) {
        store.updateData { AppModeData(mode.name) }
    }

    private fun AppModeData.toDomain(): AppMode? = AppMode.entries.firstOrNull { it.name == mode }
}
