package org.privatetracker.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetracker.app.ServiceLauncher
import org.privatetracker.app.platform.serverUrls
import org.privatetracker.app.server.ServerRuntime
import org.privatetracker.app.server.ServerState
import org.privatetracker.app.tracker.TrackerRuntime
import org.privatetracker.app.tracker.TrackerState
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UpdateTrackerConfig
import javax.inject.Inject

data class SpikeUiState(
    val server: ServerState = ServerState.Stopped,
    val serverUrls: List<String> = emptyList(),
    val devices: List<DeviceOverview> = emptyList(),
    val tracker: TrackerState = TrackerState(),
    val queueSize: Int = 0,
    val trackerConfig: TrackerConfig = TrackerConfig(),
    val message: String? = null,
)

@HiltViewModel
class SpikeViewModel @Inject constructor(
    serverRuntime: ServerRuntime,
    trackerRuntime: TrackerRuntime,
    observeDevices: ObserveDeviceOverviews,
    outbox: OutboxRepository,
    serverConfig: ServerConfigRepository,
    private val trackerConfig: TrackerConfigRepository,
    private val updateTrackerConfig: UpdateTrackerConfig,
    private val testServerConnection: TestServerConnection,
    private val launcher: ServiceLauncher,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    private val serverPart = combine(serverRuntime.state, serverConfig.observe(), observeDevices()) { server, config, devices ->
        Triple(server, if (server is ServerState.Running) serverUrls(config.port) else emptyList(), devices)
    }
    private val trackerPart = combine(trackerRuntime.state, outbox.observeCount(), trackerConfig.observe()) { tracker, queue, config ->
        Triple(tracker, queue, config)
    }

    val state: StateFlow<SpikeUiState> = combine(serverPart, trackerPart, message) { server, tracker, message ->
        SpikeUiState(
            server = server.first,
            serverUrls = server.second,
            devices = server.third,
            tracker = tracker.first,
            queueSize = tracker.second,
            trackerConfig = tracker.third,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpikeUiState())

    fun startServer() = launcher.startServer()

    fun stopServer() = launcher.stopServer()

    fun startTracking(serverUrl: String, intervalSeconds: String) {
        viewModelScope.launch {
            val requested = trackerConfig.get().copy(
                serverUrl = serverUrl,
                intervalSeconds = intervalSeconds.toIntOrNull() ?: -1,
                trackingEnabled = true,
            )
            when (val result = updateTrackerConfig(requested)) {
                is Outcome.Success -> {
                    message.value = null
                    launcher.startTracking()
                }
                is Outcome.Failure -> message.value = "Configuración inválida: ${result.error.describe()}"
            }
        }
    }

    fun stopTracking() {
        viewModelScope.launch {
            trackerConfig.update { it.copy(trackingEnabled = false) }
            launcher.stopTracking()
        }
    }

    fun testConnection(serverUrl: String) {
        viewModelScope.launch {
            message.value = "Probando $serverUrl…"
            message.value = when (val result = testServerConnection(serverUrl)) {
                is Outcome.Success -> with(result.value) {
                    "Conectado a «${server.name}» en ${latency.toMillis()} ms" + if (compatible) "" else " (protocolo incompatible)"
                }
                is Outcome.Failure -> "Sin conexión: ${result.error.describe()}"
            }
        }
    }

    private fun DomainError.describe(): String = when (this) {
        is DomainError.Validation -> violations.joinToString { "${it.field} ${it.rule}" }
        else -> code
    }
}
