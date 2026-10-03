package org.privatetracker.feature.server.host

import android.util.Log
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.usecase.server.CloseAllSessions
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.feature.server.service.ServiceServerController
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.privateTrackerApi
import java.net.BindException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the engine-agnostic [privateTrackerApi] on Netty.
 *
 * Call [start] and [stop] from a coroutine launched on [scope]: they block while Netty binds or
 * drains. Do not wrap Ktor's start() in withContext: on Android (Ktor 3.6, coroutines 1.11) start()
 * returned but the suspended caller never resumed. Found in the spike.
 */
@Singleton
class NettyServerHost @Inject constructor(
    private val dependencies: ServerDependencies,
    private val controller: ServiceServerController,
    private val closeInactiveSessions: CloseInactiveSessions,
    private val closeAllSessions: CloseAllSessions,
) {
    /**
     * Outlives the service, since stopping Netty takes up to the grace period. One thread runs starts
     * and stops in the order the service asked for them: a quick Stop then Start must not let the
     * start run first, find the old server still up, and leave the new service without one.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null

    suspend fun start(port: Int, host: String) = mutex.withLock {
        if (server != null) return@withLock
        controller.shuttingDown = false
        controller.update(ServerRunState.Starting)
        // Sessions a crash left open end at their last activity, not now.
        closeInactiveSessions()
        try {
            server = embeddedServer(Netty, port = port, host = host) { privateTrackerApi(dependencies) }.start(wait = false)
            Log.i(TAG, "Listening on $host:$port")
            controller.update(ServerRunState.Running(port))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Server failed to start on $host:$port", e)
            controller.update(ServerRunState.Failed(e.toStartError(port)))
        }
    }

    /** Answers 503 during the grace period, so trackers keep their outbox, then closes every session. */
    suspend fun stop() = mutex.withLock {
        val current = server ?: return@withLock
        controller.shuttingDown = true
        controller.update(ServerRunState.Stopping)
        current.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
        closeAllSessions()
        server = null
        controller.update(ServerRunState.Stopped)
    }

    private fun Throwable.toStartError(port: Int): DomainError =
        if (generateSequence(this) { it.cause }.any { it is BindException }) {
            DomainError.PortInUse(port)
        } else {
            DomainError.ServerStartFailed(message ?: javaClass.simpleName)
        }

    private companion object {
        const val TAG = "NettyServerHost"
    }
}
