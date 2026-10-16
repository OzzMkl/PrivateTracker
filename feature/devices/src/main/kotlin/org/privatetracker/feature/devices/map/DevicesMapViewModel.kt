package org.privatetracker.feature.devices.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.geo.outline
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.ObserveGeofences
import org.privatetracker.core.map.MapArea
import org.privatetracker.core.map.MapPoint
import javax.inject.Inject

data class DevicesMapUiState(
    /** Devices that have sent at least one location; null while loading. */
    val located: List<DeviceOverview>? = null,
    val selected: DeviceOverview? = null,
    /** Geofences as drawn: an edge of points each. */
    val geofences: List<MapArea> = emptyList(),
)

@HiltViewModel
class DevicesMapViewModel @Inject constructor(
    observeDevices: ObserveDeviceOverviews,
    observeGeofences: ObserveGeofences,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val selectedId = savedState.getStateFlow<String?>(SELECTED, null)

    val state: StateFlow<DevicesMapUiState> =
        combine(observeDevices(), selectedId, observeGeofences()) { devices, selected, geofences ->
            val located = devices.filter { it.lastLocation != null }
            val areas = geofences.map { fence -> MapArea(fence.id.value, outline(fence.shape).map { MapPoint(it.latitude, it.longitude) }) }
            DevicesMapUiState(located, located.firstOrNull { it.device.id.value == selected }, areas)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesMapUiState())

    fun onSelect(deviceId: String?) {
        savedState[SELECTED] = deviceId
    }

    private companion object {
        const val SELECTED = "selected"
    }
}
