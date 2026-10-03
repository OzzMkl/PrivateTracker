package org.privatetracker.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.ConnectionCheck
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.common.ObserveAppMode
import org.privatetracker.core.domain.usecase.common.SetAppMode
import org.privatetracker.core.domain.usecase.server.StartServer
import org.privatetracker.core.domain.usecase.server.StopServer
import org.privatetracker.core.domain.usecase.server.UpdateServerConfig
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UpdateTrackerConfig
import javax.inject.Inject

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Passed(val check: ConnectionCheck) : ConnectionTest

    /** [suggestLocalNetwork]: the server did not answer and Android 17's local network access is missing. */
    data class Failed(val error: DomainError, val suggestLocalNetwork: Boolean) : ConnectionTest
}

data class SettingsUiState(
    val mode: AppMode? = null,
    val tracker: TrackerForm? = null,
    val server: ServerForm? = null,
    val connectionTest: ConnectionTest = ConnectionTest.Idle,
    /** The saved port or interface differs from the one the running server listens on. */
    val serverRestartNeeded: Boolean = false,
    val restarting: Boolean = false,
    /** Shown once as a snackbar, then cleared with [SettingsViewModel.onSavedShown]. */
    val saved: Boolean = false,
    val appVersion: String = "",
    val protocolVersion: Int = ProtocolVersion.CURRENT,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeAppMode: ObserveAppMode,
    private val setAppMode: SetAppMode,
    private val trackerConfig: TrackerConfigRepository,
    private val serverConfig: ServerConfigRepository,
    private val updateTrackerConfig: UpdateTrackerConfig,
    private val updateServerConfig: UpdateServerConfig,
    private val testServerConnection: TestServerConnection,
    private val permissions: PermissionChecker,
    private val serverController: ServerController,
    private val startServer: StartServer,
    private val stopServer: StopServer,
    appInfo: AppInfo,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(appVersion = appInfo.version))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { observeAppMode().collect { mode -> _state.update { it.copy(mode = mode) } } }
        // Stored values replace the form only while the user has not edited it.
        viewModelScope.launch {
            trackerConfig.observe().collect { config ->
                _state.update { if (it.tracker?.dirty == true) it else it.copy(tracker = TrackerForm.from(config)) }
            }
        }
        viewModelScope.launch {
            serverConfig.observe().collect { config ->
                _state.update { if (it.server?.dirty == true) it else it.copy(server = ServerForm.from(config)) }
            }
        }
    }

    fun onModeChange(mode: AppMode) {
        viewModelScope.launch { setAppMode(mode) }
    }

    fun onTrackerChange(transform: (TrackerForm) -> TrackerForm) {
        _state.update { state ->
            state.copy(
                tracker = state.tracker?.let { transform(it).copy(dirty = true) },
                connectionTest = ConnectionTest.Idle,
            )
        }
    }

    fun onServerChange(transform: (ServerForm) -> ServerForm) {
        _state.update { state -> state.copy(server = state.server?.let { transform(it).copy(dirty = true) }) }
    }

    fun onSaveTracker() {
        val form = _state.value.tracker ?: return
        viewModelScope.launch {
            val (config, parseErrors) = form.toConfig(trackerConfig.get())
            if (parseErrors.isNotEmpty()) return@launch showTrackerErrors(parseErrors)
            when (val result = updateTrackerConfig(config)) {
                is Outcome.Success -> _state.update { it.copy(tracker = TrackerForm.from(result.value), saved = true) }
                is Outcome.Failure -> showTrackerErrors((result.error as? DomainError.Validation)?.violations.orEmpty())
            }
        }
    }

    fun onSaveServer() {
        val form = _state.value.server ?: return
        viewModelScope.launch {
            val (config, parseErrors) = form.toConfig(serverConfig.get())
            if (parseErrors.isNotEmpty()) return@launch showServerErrors(parseErrors)
            when (val result = updateServerConfig(config)) {
                is Outcome.Success -> {
                    val running = serverController.activity.value.state is ServerRunState.Running
                    _state.update {
                        it.copy(
                            server = ServerForm.from(result.value.config),
                            serverRestartNeeded = it.serverRestartNeeded || (result.value.restartRequired && running),
                            saved = true,
                        )
                    }
                }
                is Outcome.Failure -> showServerErrors((result.error as? DomainError.Validation)?.violations.orEmpty())
            }
        }
    }

    fun onTestConnection() {
        val url = _state.value.tracker?.serverUrl ?: return
        _state.update { it.copy(connectionTest = ConnectionTest.Running) }
        viewModelScope.launch {
            val test = when (val result = testServerConnection(url)) {
                is Outcome.Success -> ConnectionTest.Passed(result.value)
                is Outcome.Failure -> {
                    val noAnswer = result.error is DomainError.Network.Timeout || result.error == DomainError.Network.Unreachable
                    val localNetwork = AppPermission.LOCAL_NETWORK
                    val missing = permissions.isApplicable(localNetwork) && !permissions.isGranted(localNetwork)
                    ConnectionTest.Failed(result.error, suggestLocalNetwork = noAnswer && missing)
                }
            }
            _state.update { it.copy(connectionTest = test) }
        }
    }

    /** Stops the server, waits until Netty has let go of the port, and starts it with the new settings. */
    fun onRestartServer() {
        if (_state.value.restarting) return
        _state.update { it.copy(restarting = true) }
        viewModelScope.launch {
            stopServer()
            withTimeoutOrNull(RESTART_TIMEOUT_MILLIS) {
                serverController.activity.first { it.state is ServerRunState.Stopped || it.state is ServerRunState.Failed }
            }
            startServer()
            _state.update { it.copy(restarting = false, serverRestartNeeded = false) }
        }
    }

    fun onSavedShown() {
        _state.update { it.copy(saved = false) }
    }

    private fun showTrackerErrors(violations: List<FieldViolation>) {
        _state.update { state -> state.copy(tracker = state.tracker?.copy(errors = violations.associateBy { it.field })) }
    }

    private fun showServerErrors(violations: List<FieldViolation>) {
        _state.update { state -> state.copy(server = state.server?.copy(errors = violations.associateBy { it.field })) }
    }

    private companion object {
        const val RESTART_TIMEOUT_MILLIS = 10_000L
    }
}
