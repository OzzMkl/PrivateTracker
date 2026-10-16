package org.privatetracker.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.privatetracker.core.data.mapper.millis
import org.privatetracker.core.data.mapper.toDomain
import org.privatetracker.core.data.mapper.toOutboxEntity
import org.privatetracker.core.database.tracker.OutboxDao
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.repository.OutboxRepository
import java.time.Instant

class RoomOutboxRepository(private val dao: OutboxDao) : OutboxRepository {
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
