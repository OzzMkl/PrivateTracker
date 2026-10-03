package org.privatetracker.feature.server.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.ServerActivity
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.port.ServerController
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Starts [ServerService] and holds what the server reports, for the screen and the API. */
@Singleton
class ServiceServerController @Inject constructor(
    @ApplicationContext private val context: Context,
) : ServerController {
    private val _activity = MutableStateFlow(ServerActivity())
    override val activity: StateFlow<ServerActivity> = _activity.asStateFlow()
    private val requests = AtomicLong()

    /** While true the API answers 503, so trackers keep their outbox during the shutdown grace period. */
    @Volatile
    internal var shuttingDown: Boolean = false

    internal fun update(state: ServerRunState) {
        if (state == ServerRunState.Starting) requests.set(0)
        _activity.value = ServerActivity(state, requests.get())
    }

    internal fun countRequest() {
        val served = requests.incrementAndGet()
        _activity.update { it.copy(requestsServed = served) }
    }

    override fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a start from the background (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "Server service not started", e)
        }
    }

    override fun stop() {
        context.stopService(Intent(context, ServerService::class.java))
    }

    private companion object {
        const val TAG = "ServerController"
    }
}
