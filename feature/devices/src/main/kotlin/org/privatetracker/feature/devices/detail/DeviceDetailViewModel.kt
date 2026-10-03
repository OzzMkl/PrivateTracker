package org.privatetracker.feature.devices.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceDetail
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.usecase.server.ObserveDeviceDetail
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.usecase.server.RenameDevice
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval

data class DeviceDetailUiState(
    val loading: Boolean = true,
    /** Null once loaded means the device no longer exists. */
    val detail: DeviceDetail? = null,
    val renameError: FieldViolation? = null,
)

@HiltViewModel(assistedFactory = DeviceDetailViewModel.Factory::class)
class DeviceDetailViewModel @AssistedInject constructor(
    @Assisted rawDeviceId: String,
    observeDetail: ObserveDeviceDetail,
    private val renameDevice: RenameDevice,
    private val removeDevice: RemoveDevice,
    private val setApproval: SetDeviceApproval,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(deviceId: String): DeviceDetailViewModel
    }

    private val deviceId = DeviceId.parse(rawDeviceId)
    private val renameError = MutableStateFlow<FieldViolation?>(null)

    val state: StateFlow<DeviceDetailUiState> =
        combine(deviceId?.let { observeDetail(it) } ?: flowOf(null), renameError) { detail, error ->
            DeviceDetailUiState(loading = false, detail = detail, renameError = error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState())

    /** Calls [onRenamed] when the new name was saved; a rejected name shows in [DeviceDetailUiState.renameError]. */
    fun onRename(name: String, onRenamed: () -> Unit) {
        val id = deviceId ?: return
        viewModelScope.launch {
            when (val result = renameDevice(id, name)) {
                is Outcome.Success -> {
                    renameError.value = null
                    onRenamed()
                }
                is Outcome.Failure -> renameError.value = (result.error as? DomainError.Validation)?.violations?.firstOrNull()
            }
        }
    }

    fun onClearRenameError() {
        renameError.value = null
    }

    fun onDecide(approval: DeviceApproval) {
        val id = deviceId ?: return
        viewModelScope.launch { setApproval(id, approval) }
    }

    /** The screen leaves on its own once the device is gone, since the detail becomes null. */
    fun onRemove() {
        val id = deviceId ?: return
        viewModelScope.launch { removeDevice(id) }
    }
}
