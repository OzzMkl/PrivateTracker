package org.privatetracker.feature.server.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.ObservePairedDevice
import org.privatetracker.core.domain.usecase.server.ObserveServerStatus
import org.privatetracker.core.domain.usecase.server.WithdrawPairingInvite
import org.privatetracker.core.protocol.v1.PairingUri
import javax.inject.Inject

data class PairingUiState(
    /** Null while the invite is being created. */
    val invite: PairingInvite? = null,
    /** What the QR code holds. */
    val link: String? = null,
    val error: DomainError? = null,
    /** The tracker that used the code; the screen then says so. */
    val paired: Device? = null,
    val serverKeyFingerprint: String? = null,
)

/** Creates a one-time invite as soon as the server has addresses, and watches who uses it. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val observeStatus: ObserveServerStatus,
    private val createInvite: CreatePairingInvite,
    private val withdrawInvite: WithdrawPairingInvite,
    observePaired: ObservePairedDevice,
    getServerKeyFingerprint: GetServerKeyFingerprint,
) : ViewModel() {
    private val invite = MutableStateFlow<Outcome<PairingInvite>?>(null)
    private val fingerprint = MutableStateFlow<String?>(null)
    private var renewing: Job? = null

    val state: StateFlow<PairingUiState> =
        combine(
            invite,
            invite.flatMapLatest { outcome -> (outcome as? Outcome.Success)?.value?.let { observePaired(it.ticketId) } ?: flowOf(null) },
            fingerprint,
        ) { outcome, paired, serverFingerprint ->
            val created = (outcome as? Outcome.Success)?.value
            PairingUiState(
                invite = created,
                link = created?.let(PairingUri::format),
                error = (outcome as? Outcome.Failure)?.error,
                paired = paired,
                serverKeyFingerprint = serverFingerprint,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    init {
        viewModelScope.launch { fingerprint.value = getServerKeyFingerprint() }
        onRenew()
    }

    /** A new code; the previous one stops working if nobody used it. A second tap restarts, never doubles. */
    fun onRenew() {
        renewing?.cancel()
        renewing = viewModelScope.launch {
            invite.value = null
            // Loopback is useless to another phone and only makes the code denser.
            val urls = observeStatus()
                .map { status -> status.addresses.filter { it.kind != AddressKind.LOOPBACK }.map { it.url } }
                .first { it.isNotEmpty() }
            invite.value = createInvite(urls)
        }
    }

    /** Leaving the screen ends the code: a photo of it should not work later. */
    override fun onCleared() {
        (invite.value as? Outcome.Success)?.value?.let { withdrawInvite(it.ticketId) }
    }
}
