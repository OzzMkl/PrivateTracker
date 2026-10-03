package org.privatetracker.feature.tracker.ui

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
import org.privatetracker.core.common.result.errorOrNull
import org.privatetracker.core.domain.model.TrackingStatus
import org.privatetracker.core.domain.usecase.tracker.GetDeviceKeyFingerprint
import org.privatetracker.core.domain.usecase.tracker.ObserveTrackingStatus
import org.privatetracker.core.domain.usecase.tracker.StartTracking
import org.privatetracker.core.domain.usecase.tracker.StopTracking
import javax.inject.Inject

data class TrackerUiState(
    /** Null while loading. */
    val status: TrackingStatus? = null,
    /** Why the last tap on Start did nothing. */
    val startError: DomainError? = null,
    val busy: Boolean = false,
    /** What the server's owner compares before approving this phone; null until the key is read. */
    val keyFingerprint: String? = null,
)

@HiltViewModel
class TrackerViewModel @Inject constructor(
    observeStatus: ObserveTrackingStatus,
    getKeyFingerprint: GetDeviceKeyFingerprint,
    private val startTracking: StartTracking,
    private val stopTracking: StopTracking,
) : ViewModel() {
    private val startError = MutableStateFlow<DomainError?>(null)
    private val busy = MutableStateFlow(false)
    private val keyFingerprint = flow<String?> { emit(getKeyFingerprint()) }.onStart { emit(null) }

    val state: StateFlow<TrackerUiState> =
        combine(observeStatus(), startError, busy, keyFingerprint, ::TrackerUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackerUiState())

    fun onStart() = act { startError.value = startTracking().errorOrNull() }

    fun onStop() = act {
        startError.value = null
        stopTracking()
    }

    private fun act(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }
}
