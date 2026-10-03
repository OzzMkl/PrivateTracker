package org.privatetracker.feature.tracker.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.usecase.tracker.CurrentServer
import org.privatetracker.core.domain.usecase.tracker.GetCurrentServer
import org.privatetracker.core.domain.usecase.tracker.PairWithServer
import org.privatetracker.core.domain.usecase.tracker.PairingResult
import org.privatetracker.core.protocol.v1.PairingUri

sealed interface PairingStep {
    data object Scanning : PairingStep
    data class Confirm(val invite: PairingInvite) : PairingStep
    data class Pairing(val invite: PairingInvite) : PairingStep
    data class Paired(val result: PairingResult) : PairingStep
    data class Failed(val invite: PairingInvite, val error: DomainError) : PairingStep
}

data class TrackerPairingUiState(
    val step: PairingStep = PairingStep.Scanning,
    /** The last code read was not a PrivateTracker invite. */
    val foreignCode: Boolean = false,
    /** The server this phone sends to now; the confirmation warns before replacing it. */
    val current: CurrentServer? = null,
)

/** Scan, confirm, pair. Opened from a pairing link, it starts at the confirmation. */
@HiltViewModel(assistedFactory = TrackerPairingViewModel.Factory::class)
class TrackerPairingViewModel @AssistedInject constructor(
    @Assisted link: String?,
    private val pairWithServer: PairWithServer,
    private val getCurrentServer: GetCurrentServer,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(link: String?): TrackerPairingViewModel
    }

    private val mutableState = MutableStateFlow(TrackerPairingUiState())
    val state: StateFlow<TrackerPairingUiState> = mutableState.asStateFlow()

    init {
        if (link != null) onScanned(link)
        viewModelScope.launch {
            val current = getCurrentServer()
            mutableState.update { it.copy(current = current) }
        }
    }

    /** True when the text was an invite, which ends scanning. */
    fun onScanned(text: String): Boolean {
        val invite = PairingUri.parse(text)
        mutableState.update { state ->
            if (invite == null) state.copy(foreignCode = true) else state.copy(step = PairingStep.Confirm(invite), foreignCode = false)
        }
        return invite != null
    }

    fun onConfirm() {
        val invite = (mutableState.value.step as? PairingStep.Confirm)?.invite ?: return
        mutableState.update { it.copy(step = PairingStep.Pairing(invite)) }
        viewModelScope.launch {
            val step = when (val result = pairWithServer(invite)) {
                is Outcome.Success -> PairingStep.Paired(result.value)
                is Outcome.Failure -> PairingStep.Failed(invite, result.error)
            }
            mutableState.update { it.copy(step = step) }
        }
    }

    fun onScanAgain() {
        mutableState.update { it.copy(step = PairingStep.Scanning, foreignCode = false) }
    }
}
