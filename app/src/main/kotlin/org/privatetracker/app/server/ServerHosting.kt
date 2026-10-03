package org.privatetracker.app.server

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.app.di.ApplicationScope
import org.privatetracker.app.notification.Notifications
import org.privatetracker.app.work.WorkScheduler
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.usecase.server.CloseAllSessions
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.privateTrackerApi
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int) : ServerState
    data class Failed(val message: String) : ServerState
}

/** Observable state of the embedded server, shared by the service, the host and the UI. */
@Singleton
class ServerRuntime @Inject constructor() {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    @Volatile
    var shuttingDown: Boolean = false

    fun update(state: ServerState) {
        _state.value = state
    }
}

/**
 * Runs the engine-agnostic [privateTrackerApi] on Netty.
 *
 * Call [start] and [stop] from a coroutine already running on [Dispatchers.IO]: they block while
 * Netty binds or drains. Do not wrap Ktor's start() in withContext: on Android (Ktor 3.6,
 * coroutines 1.11) start() returned but the suspended caller never resumed. Found in the spike.
 */
@Singleton
class NettyServerHost @Inject constructor(
    private val dependencies: ServerDependencies,
    private val runtime: ServerRuntime,
    private val closeAllSessions: CloseAllSessions,
) {
    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null

    suspend fun start(port: Int, host: String) = mutex.withLock {
        if (server != null) return@withLock
        runtime.shuttingDown = false
        runtime.update(ServerState.Starting)
        try {
            server = embeddedServer(Netty, port = port, host = host) { privateTrackerApi(dependencies) }.start(wait = false)
            Log.i(TAG, "Listening on $host:$port")
            runtime.update(ServerState.Running(port))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Server failed to start on $host:$port", e)
            runtime.update(ServerState.Failed(e.message ?: e::class.java.simpleName))
        }
    }

    /** Answers 503 during the grace period, so trackers keep their outbox, then closes every session. */
    suspend fun stop() = mutex.withLock {
        val current = server ?: return@withLock
        runtime.shuttingDown = true
        current.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
        closeAllSessions()
        server = null
        runtime.update(ServerState.Stopped)
    }

    private companion object {
        const val TAG = "NettyServerHost"
    }
}

@AndroidEntryPoint
class ServerService : Service() {
    @Inject lateinit var host: NettyServerHost
    @Inject lateinit var serverConfig: ServerConfigRepository
    @Inject lateinit var runtime: ServerRuntime
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SERVER,
            Notifications.server(this, "Iniciando…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        workScheduler.scheduleServerMaintenance()
        appScope.launch(Dispatchers.IO) {
            val config = serverConfig.get()
            host.start(config.port, config.bindAddress)
            val text = when (val state = runtime.state.value) {
                is ServerState.Running -> "Escuchando en el puerto ${state.port}"
                is ServerState.Failed -> "Error: ${state.message}"
                else -> "Detenido"
            }
            Notifications.update(this@ServerService, Notifications.ID_SERVER, Notifications.server(this@ServerService, text))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Outlives the service: stopping Netty takes up to the grace period.
        appScope.launch(Dispatchers.IO) { host.stop() }
        workScheduler.cancelServerMaintenance()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
