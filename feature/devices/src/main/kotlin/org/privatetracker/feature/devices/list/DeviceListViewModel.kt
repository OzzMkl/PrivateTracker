package org.privatetracker.feature.devices.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval
import javax.inject.Inject

/** Devices most recently seen first; null while loading. Pending ones can be approved from the list. */
@HiltViewModel
class DeviceListViewModel @Inject constructor(
    observeDevices: ObserveDeviceOverviews,
    private val setApproval: SetDeviceApproval,
) : ViewModel() {
    val devices: StateFlow<List<DeviceOverview>?> =
        observeDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onDecide(id: DeviceId, approval: DeviceApproval) {
        viewModelScope.launch { setApproval(id, approval) }
    }
}
