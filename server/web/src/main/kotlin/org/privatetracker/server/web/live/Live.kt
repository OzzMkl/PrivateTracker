package org.privatetracker.server.web.live

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.liveAlertsJson
import org.privatetracker.server.web.page.mapDevicesJson
import org.privatetracker.server.web.security.requireSession
import org.privatetracker.server.web.security.sessionToken
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Geofence alerts for the open pages of the web app; a browser's tabs share one stream, so this counts
 * browsers. The host's GeofenceAlerts publishes here; while no page is open, [listening] is false and the crossing is stored as not alerted, unless a webhook
 * takes it. A stream counts as listening from the moment it subscribed, not before, so an alert is
 * never marked as seen by a page that could not have received it.
 */
class LiveAlerts {
    private val listeners = AtomicInteger()

    // A page too slow to keep up loses its oldest alerts; the others and the ingest never wait for it.
    private val events = MutableSharedFlow<List<GeofenceEventView>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val listening: Boolean get() = listeners.get() > 0
    val listenerCount: Int get() = listeners.get()

    /** Never blocks. */
    fun publish(alerts: List<GeofenceEventView>) {
        events.tryEmit(alerts)
    }

    internal fun alerts(): Flow<List<GeofenceEventView>> = flow {
        var counted = false
        try {
            events.onSubscription {
                listeners.incrementAndGet()
                counted = true
            }.collect { emit(it) }
        } finally {
            if (counted) listeners.decrementAndGet()
        }
    }
}

/** A stream that stopped draining, like a frozen background tab: it is ended rather than kept. */
private val STALLED_AFTER = 30.seconds

/** The session behind a stream ended: signed out, its password changed, or it expired. */
private class SessionEnded : RuntimeException()

/**
 * `GET /live`: one Server-Sent Events stream per open page. `devices` brings the map's devices whenever
 * they change (and at least every 30 s, as their status depends on the clock), `alert` the geofence
 * crossings. A comment every [WebDependencies.liveHeartbeat] (15 s) keeps proxies from closing it and
 * finds out a closed tab; the session is checked as often, and a stream whose session ended says
 * `signed-out` and closes.
 */
internal fun Route.liveRoutes(deps: WebDependencies) {
    get("/live") {
        call.requireSession(deps)
        val token = call.sessionToken()
        call.response.cacheControl(CacheControl.NoStore(null))
        call.response.header("X-Accel-Buffering", "no")
        try {
            call.stream(deps, token)
        } catch (e: IOException) {
            // The page closed or moved on (Ktor's closed-channel errors are IOExceptions too); every stream ends so.
        }
    }
}

private suspend fun ApplicationCall.stream(deps: WebDependencies, token: String?) {
    respondBytesWriter(ContentType.Text.EventStream) {
        val lock = Mutex()
        suspend fun ByteWriteChannel.emit(text: String) = lock.withLock {
            try {
                withTimeout(STALLED_AFTER) {
                    writeStringUtf8(text)
                    flush()
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("The page stopped reading", e)
            }
        }
        suspend fun ByteWriteChannel.event(name: String, data: String) = emit("event: $name\ndata: $data\n\n")
        try {
            coroutineScope {
                // Subscribed before anything is sent: a page that got its first event also gets every alert after it.
                launch(start = CoroutineStart.UNDISPATCHED) { deps.alerts.alerts().collect { event("alert", liveAlertsJson(it)) } }
                emit("retry: 5000\n\n")
                launch {
                    while (true) {
                        delay(deps.liveHeartbeat)
                        if (deps.authenticate(token) == null) {
                            event("signed-out", "{}")
                            throw SessionEnded()
                        }
                        emit(": ping\n\n")
                    }
                }
                deps.observeDeviceOverviews().collect { event("devices", mapDevicesJson(it)) }
            }
        } catch (e: SessionEnded) {
            // The page asks /session whether this browser still has a session (another tab may have a new one).
        }
    }
}
