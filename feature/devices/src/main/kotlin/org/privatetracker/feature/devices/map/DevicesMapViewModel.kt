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
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import javax.inject.Inject

data class DevicesMapUiState(
    /** Devices that have sent at least one location; null while loading. */
    val located: List<DeviceOverview>? = null,
    val selected: DeviceOverview? = null,
)

@HiltViewModel
class DevicesMapViewModel @Inject constructor(
    observeDevices: ObserveDeviceOverviews,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val selectedId = savedState.getStateFlow<String?>(SELECTED, null)

    val state: StateFlow<DevicesMapUiState> =
        combine(observeDevices(), selectedId) { devices, selected ->
            val located = devices.filter { it.lastLocation != null }
            DevicesMapUiState(located, located.firstOrNull { it.device.id.value == selected })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesMapUiState())

    fun onSelect(deviceId: String?) {
        savedState[SELECTED] = deviceId
    }

    private companion object {
        const val SELECTED = "selected"
    }
}
