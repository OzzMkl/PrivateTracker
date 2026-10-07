package org.privatetracker.feature.server.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.errorOrNull
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.ServerStatus
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.ObserveEncryptionKey
import org.privatetracker.core.domain.usecase.server.ObserveServerStatus
import org.privatetracker.core.domain.usecase.server.RotateEncryptionKey
import org.privatetracker.core.domain.usecase.server.StartServer
import org.privatetracker.core.domain.usecase.server.StopServer
import javax.inject.Inject

data class ServerUiState(
    /** Null while loading. */
    val status: ServerStatus? = null,
    val deviceCount: Int = 0,
    /** Why the last tap on Start did nothing. */
    val startError: DomainError? = null,
    /** Devices that asked to join and wait for the owner's decision. */
    val pendingCount: Int = 0,
    /** What paired trackers show as their server's fingerprint. */
    val serverKeyFingerprint: String? = null,
    /** The key trackers seal their requests for; null while loading or when the key store fails. */
    val encryptionKey: EncryptionKey? = null,
    /** Why the last rotation failed. */
    val rotateError: DomainError? = null,
)

@HiltViewModel
class ServerViewModel @Inject constructor(
    observeStatus: ObserveServerStatus,
    observeDevices: ObserveDeviceOverviews,
    getServerKeyFingerprint: GetServerKeyFingerprint,
    observeEncryptionKey: ObserveEncryptionKey,
    private val rotateEncryptionKey: RotateEncryptionKey,
    private val startServer: StartServer,
    private val stopServer: StopServer,
) : ViewModel() {
    private val startError = MutableStateFlow<DomainError?>(null)
    private val rotateError = MutableStateFlow<DomainError?>(null)
    private val fingerprint = flow<String?> { emit(getServerKeyFingerprint()) }.onStart { emit(null) }
    private val keys = combine(fingerprint, observeEncryptionKey().onStart { emit(null) }, rotateError, ::Keys)

    val state: StateFlow<ServerUiState> =
        combine(observeStatus(), observeDevices(), startError, keys) { status, devices, error, keys ->
            ServerUiState(
                status = status,
                deviceCount = devices.size,
                startError = error,
                pendingCount = devices.count { it.device.awaitsApproval },
                serverKeyFingerprint = keys.fingerprint,
                encryptionKey = keys.encryptionKey,
                rotateError = keys.rotateError,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerUiState())

    private class Keys(val fingerprint: String?, val encryptionKey: EncryptionKey?, val rotateError: DomainError?)

    /** [onDone] runs after a successful rotation, for the screen to confirm it. */
    fun onRotateEncryptionKey(onDone: () -> Unit) {
        viewModelScope.launch {
            val result = rotateEncryptionKey()
            rotateError.value = result.errorOrNull()
            if (result is Outcome.Success) onDone()
        }
    }

    fun onStart() {
        startError.value = startServer().errorOrNull()
    }

    fun onStop() {
        startError.value = null
        stopServer()
    }
}
