package org.privatetracker.feature.geofences.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.core.domain.usecase.server.ObserveGeofenceEvents
import org.privatetracker.core.domain.usecase.server.ObserveGeofences
import javax.inject.Inject

data class GeofencesUiState(
    /** Null while loading. */
    val geofences: List<Geofence>? = null,
    val events: List<GeofenceEventView> = emptyList(),
    /** False when notifications are blocked on this phone: crossings would be recorded without sounding. */
    val audible: Boolean = true,
) {
    /** Some geofence wants to sound, and cannot. */
    val muted: Boolean get() = !audible && geofences.orEmpty().any { it.notifyOnEnter || it.notifyOnExit }
}

@HiltViewModel
class GeofencesViewModel @Inject constructor(
    observeGeofences: ObserveGeofences,
    observeEvents: ObserveGeofenceEvents,
    private val alerts: GeofenceAlerts,
) : ViewModel() {
    private val audible = MutableStateFlow(alerts.canAlert())

    val state: StateFlow<GeofencesUiState> =
        combine(observeGeofences(), observeEvents(), audible) { geofences, events, audible -> GeofencesUiState(geofences, events, audible) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeofencesUiState())

    /** On return to the screen: the owner may have changed notifications in system settings. */
    fun refresh() {
        audible.value = alerts.canAlert()
    }
}
