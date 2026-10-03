package org.privatetracker.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.usecase.common.ObserveAppMode
import org.privatetracker.core.domain.usecase.tracker.RestoreTracking
import org.privatetracker.core.domain.usecase.tracker.RestoreTrigger
import javax.inject.Inject

sealed interface AppUiState {
    data object Loading : AppUiState
    data object Onboarding : AppUiState
    data class Ready(val mode: AppMode) : AppUiState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    observeAppMode: ObserveAppMode,
    restoreTracking: RestoreTracking,
) : ViewModel() {
    val state: StateFlow<AppUiState> =
        observeAppMode()
            .map { mode -> if (mode == null) AppUiState.Onboarding else AppUiState.Ready(mode) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading)

    init {
        // Once per process, while the app is visible: Android lets a foreground app start the service.
        viewModelScope.launch { restoreTracking(RestoreTrigger.APP_OPENED) }
    }
}
