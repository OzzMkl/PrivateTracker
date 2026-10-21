package org.privatetracker.feature.server.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.advertisedAddresses
import org.privatetracker.core.domain.usecase.server.CreatePeerInvite
import org.privatetracker.core.domain.usecase.server.ExchangeWithPeer
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.JoinPeer
import org.privatetracker.core.domain.usecase.server.ObservePeerJoined
import org.privatetracker.core.domain.usecase.server.ObservePeers
import org.privatetracker.core.domain.usecase.server.ObserveServerStatus
import org.privatetracker.core.domain.usecase.server.RemovePeer
import org.privatetracker.core.domain.usecase.server.WithdrawPeerInvite
import org.privatetracker.core.protocol.v1.PairingUri
import javax.inject.Inject

data class PeersUiState(val peers: List<Peer> = emptyList(), val loading: Boolean = true)

/** The paired servers, from 1.0, and leaving one. */
@HiltViewModel
class PeersViewModel @Inject constructor(
    observePeers: ObservePeers,
    private val removePeer: RemovePeer,
) : ViewModel() {
    val state: StateFlow<PeersUiState> = observePeers()
        .map { PeersUiState(it, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeersUiState())

    fun onRemove(peer: Peer) {
        viewModelScope.launch { removePeer(peer.publicKey) }
    }
}

data class PeerInviteUiState(
    /** Null while the invite is being created. */
    val invite: PairingInvite? = null,
    /** What the QR code holds, and what another Linux server pastes. */
    val link: String? = null,
    val error: DomainError? = null,
    /** The server that used the invite; the screen then says so. */
    val joined: Peer? = null,
    val serverKeyFingerprint: String? = null,
)

/** Like a tracker's QR, for another server: made once the server has addresses, withdrawn when the screen goes. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PeerInviteViewModel @Inject constructor(
    private val observeStatus: ObserveServerStatus,
    private val createInvite: CreatePeerInvite,
    private val withdrawInvite: WithdrawPeerInvite,
    observeJoined: ObservePeerJoined,
    private val getServerKeyFingerprint: GetServerKeyFingerprint,
) : ViewModel() {
    private val invite = MutableStateFlow<Outcome<PairingInvite>?>(null)
    private val fingerprint = MutableStateFlow<String?>(null)
    private var renewing: Job? = null

    val state: StateFlow<PeerInviteUiState> =
        combine(
            invite,
            invite.flatMapLatest { outcome -> (outcome as? Outcome.Success)?.value?.let { observeJoined(it.ticketId) } ?: flowOf(null) },
            fingerprint,
        ) { outcome, joined, serverFingerprint ->
            val created = (outcome as? Outcome.Success)?.value
            PeerInviteUiState(
                invite = created,
                link = created?.let { PairingUri.format(it, PairingUri.PEER_HOST) },
                error = (outcome as? Outcome.Failure)?.error,
                joined = joined,
                serverKeyFingerprint = serverFingerprint,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeerInviteUiState())

    init {
        viewModelScope.launch { fingerprint.value = getServerKeyFingerprint() }
        onRenew()
    }

    fun onRenew() {
        renewing?.cancel()
        renewing = viewModelScope.launch {
            invite.value = null
            val serverFingerprint = getServerKeyFingerprint()
            // The relay's name matters most here: two servers rarely share a network.
            val urls = observeStatus()
                .map { status -> advertisedAddresses(status.addresses.filter { it.kind != AddressKind.LOOPBACK }.map { it.url }, status.config, serverFingerprint) }
                .first { it.isNotEmpty() }
            invite.value = createInvite(urls)
        }
    }

    override fun onCleared() {
        (invite.value as? Outcome.Success)?.value?.let { withdrawInvite(it.ticketId) }
    }
}

sealed interface JoinStep {
    data object Reading : JoinStep
    data class Confirm(val invite: PairingInvite) : JoinStep
    data class Joining(val invite: PairingInvite) : JoinStep
    data class Joined(val peer: Peer) : JoinStep
    data class Failed(val invite: PairingInvite, val error: DomainError) : JoinStep
}

data class PeerJoinUiState(
    val step: JoinStep = JoinStep.Reading,
    /** The last code or text read was not a server's invite: a tracker's QR, say. */
    val foreignCode: Boolean = false,
    /** Exchanges run only while this phone's server does; joining while it is stopped deserves a word. */
    val serverRunning: Boolean = true,
)

/** Read another server's invite (camera or pasted link), confirm, join. Opened from a link, it starts at the confirmation. */
@HiltViewModel(assistedFactory = PeerJoinViewModel.Factory::class)
class PeerJoinViewModel @AssistedInject constructor(
    @Assisted link: String?,
    private val joinPeer: JoinPeer,
    private val exchangeWithPeer: ExchangeWithPeer,
    observeStatus: ObserveServerStatus,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(link: String?): PeerJoinViewModel
    }

    private val mutableState = MutableStateFlow(PeerJoinUiState())
    val state: StateFlow<PeerJoinUiState> = mutableState.asStateFlow()

    init {
        if (link != null) onRead(link)
        viewModelScope.launch {
            observeStatus().collect { status -> mutableState.update { it.copy(serverRunning = status.state is ServerRunState.Running) } }
        }
    }

    /** True when the text was a server's invite, which ends scanning. */
    fun onRead(text: String): Boolean {
        val invite = PairingUri.parse(text, PairingUri.PEER_HOST)
        mutableState.update { state ->
            if (invite == null) state.copy(foreignCode = true) else state.copy(step = JoinStep.Confirm(invite), foreignCode = false)
        }
        return invite != null
    }

    fun onConfirm() {
        val invite = (mutableState.value.step as? JoinStep.Confirm)?.invite ?: return
        mutableState.update { it.copy(step = JoinStep.Joining(invite)) }
        viewModelScope.launch {
            val step = when (val joined = joinPeer(invite)) {
                is Outcome.Success -> JoinStep.Joined(joined.value)
                is Outcome.Failure -> JoinStep.Failed(invite, joined.error)
            }
            mutableState.update { it.copy(step = step) }
            // The first exchange now, rather than at the service's next look; leaving the screen does not stop it.
            (step as? JoinStep.Joined)?.let { withContext(NonCancellable) { exchangeWithPeer(it.peer.publicKey) } }
        }
    }

    fun onReadAgain() {
        mutableState.update { PeerJoinUiState() }
    }
}
