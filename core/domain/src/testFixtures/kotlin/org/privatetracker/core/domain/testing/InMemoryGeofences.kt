package org.privatetracker.core.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEvent
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceState
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.GeofenceRepository
import java.time.Instant

/** Geofences, states and events in memory. Device names for the event views come from [devices]. */
class InMemoryGeofences(private val devices: DeviceRepository? = null) : GeofenceRepository {
    private val fences = MutableStateFlow<Map<GeofenceId, Geofence>>(emptyMap())
    private val states = mutableMapOf<Pair<GeofenceId, DeviceId>, GeofenceState>()
    private val events = MutableStateFlow<List<GeofenceEvent>>(emptyList())

    val storedEvents: List<GeofenceEvent> get() = events.value
    val storedStates: Map<Pair<GeofenceId, DeviceId>, GeofenceState> get() = states.toMap()

    override fun observeAll(): Flow<List<Geofence>> = fences.map { it.values.toList() }
    override suspend fun all(): List<Geofence> = fences.value.values.toList()
    override suspend fun get(id: GeofenceId): Geofence? = fences.value[id]

    override suspend fun save(geofence: Geofence, keepStates: Boolean) {
        fences.update { it + (geofence.id to geofence) }
        states.keys.removeAll { (fence, device) -> fence == geofence.id && (!keepStates || !geofence.watches(device)) }
    }

    override suspend fun delete(id: GeofenceId): Boolean {
        val existed = id in fences.value
        fences.update { it - id }
        states.keys.removeAll { it.first == id }
        events.update { all -> all.filterNot { it.geofenceId == id } }
        return existed
    }

    override suspend fun state(geofenceId: GeofenceId, deviceId: DeviceId): GeofenceState? = states[geofenceId to deviceId]

    override suspend fun saveState(state: GeofenceState) {
        states[state.geofenceId to state.deviceId] = state
    }

    override suspend fun addEvent(event: GeofenceEvent) {
        events.update { it + event }
    }

    override fun observeEvents(limit: Int): Flow<List<GeofenceEventView>> = events.map { all ->
        all.sortedByDescending { it.occurredAt }.take(limit).map { event ->
            GeofenceEventView(event, fences.value[event.geofenceId]?.name.orEmpty(), devices?.get(event.deviceId)?.name.orEmpty())
        }
    }

    override suspend fun deleteEventsBefore(cutoff: Instant): Int {
        val before = events.value.size
        events.update { all -> all.filterNot { it.detectedAt.isBefore(cutoff) } }
        return before - events.value.size
    }
}

/** Remembers every alert instead of showing a notification; [blocked] plays notifications the owner turned off. */
class RecordingGeofenceAlerts(var blocked: Boolean = false) : GeofenceAlerts {
    val alerted = mutableListOf<GeofenceEventView>()
    override fun canAlert(): Boolean = !blocked
    override fun alert(events: List<GeofenceEventView>) {
        alerted += events
    }
}
