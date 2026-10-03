package org.privatetracker.core.domain.repository

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.PendingLocation
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
import java.time.Instant

// Tracker-side storage. Implementations throw only on unexpected storage failures.

/** Locations captured but not yet acknowledged by the server, oldest first. */
interface OutboxRepository {
    /** Appends [location]; if the queue then exceeds [maxSize], drops the oldest. Returns how many were dropped. */
    suspend fun enqueue(location: Location, maxSize: Int): Int
    suspend fun peek(limit: Int): List<PendingLocation>
    suspend fun remove(ids: Collection<LocationId>)
    suspend fun markAttempt(ids: Collection<LocationId>, at: Instant, errorCode: String)
    suspend fun count(): Int
    fun observeCount(): Flow<Int>
}

/** Small pieces of tracker state that must survive process death. */
interface TrackerStateRepository {
    suspend fun lastRecorded(): Location?
    suspend fun setLastRecorded(location: Location)
    suspend fun registration(): TrackerRegistration?
    suspend fun setRegistration(registration: TrackerRegistration?)
}

interface IdentityRepository {
    /** Returns the stored device id, or stores and returns [create]'s result. Must be atomic. */
    suspend fun getOrCreate(create: () -> DeviceId): DeviceId
}

interface TrackerConfigRepository {
    fun observe(): Flow<TrackerConfig>
    suspend fun get(): TrackerConfig
    suspend fun update(transform: (TrackerConfig) -> TrackerConfig): TrackerConfig
}
