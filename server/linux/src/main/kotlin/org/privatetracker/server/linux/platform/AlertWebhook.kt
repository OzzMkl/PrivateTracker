package org.privatetracker.server.linux.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.server.web.WebhookStatus
import org.privatetracker.server.web.live.LiveAlerts
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/**
 * How the webhook's body looks. [JSON] suits Gotify (it reads `title`, `message` and `priority`), Home
 * Assistant and most automation tools; [NTFY] posts the plain text to an ntfy topic URL, with a title.
 */
enum class WebhookFormat { JSON, NTFY }

data class WebhookSettings(val url: URI, val format: WebhookFormat, val token: String?)

/**
 * Sends geofence alerts to a URL of the owner's, so they arrive with every page of the web app closed.
 * Names, the crossing and its time travel; coordinates never do, whoever runs the service at the
 * other end. Each crossing is tried three times (now, after 5 s, after 30 s), off the ingest's path.
 */
class AlertWebhook(
    private val settings: WebhookSettings,
    private val serverName: String,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val retryDelays: List<kotlin.time.Duration> = listOf(5.seconds, 30.seconds),
) {
    private val log = LoggerFactory.getLogger("AlertWebhook")

    @Volatile private var lastSentAt: Instant? = null
    @Volatile private var lastError: String? = null

    /** Scheme, host and port only: the path and query of a webhook URL often hold its secret. */
    val target: String
        get() = settings.url.let { "${it.scheme}://${it.host}${if (it.port == -1) "" else ":${it.port}"}" }

    val status: WebhookStatus get() = WebhookStatus(target, lastSentAt, lastError)

    fun send(events: List<GeofenceEventView>) {
        events.forEach { event -> scope.launch { deliver(event) } }
    }

    private suspend fun deliver(event: GeofenceEventView) {
        val request = request(event)
        for (attempt in 0..retryDelays.size) {
            if (attempt > 0) delay(retryDelays[attempt - 1])
            val failure = try {
                val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
                when (response.statusCode()) {
                    in 200..299 -> null
                    // The other end refused it as it is: sending it again changes nothing.
                    in 400..499 -> if (response.statusCode() == 429) "HTTP 429" else return failed("HTTP ${response.statusCode()}")
                    else -> "HTTP ${response.statusCode()}"
                }
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            }
            if (failure == null) {
                lastSentAt = clock.now()
                lastError = null
                return
            }
            log.warn("Webhook to {} failed (attempt {}): {}", target, attempt + 1, failure)
            lastError = failure
        }
    }

    private fun failed(reason: String) {
        log.warn("Webhook to {} refused the alert: {}", target, reason)
        lastError = reason
    }

    private fun request(event: GeofenceEventView): HttpRequest {
        val text = text(event)
        val builder = HttpRequest.newBuilder(settings.url).timeout(Duration.ofSeconds(15))
        settings.token?.let { builder.header("Authorization", "Bearer $it") }
        return when (settings.format) {
            WebhookFormat.NTFY -> builder
                .header("Content-Type", "text/plain; charset=utf-8")
                // Header values stay ASCII; the words with accents go in the body.
                .header("Title", "PrivateTracker")
                .header("Tags", "round_pushpin")
                .POST(HttpRequest.BodyPublishers.ofString(text))
                .build()
            WebhookFormat.JSON -> builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(event, text)))
                .build()
        }
    }

    private fun text(view: GeofenceEventView): String {
        val verb = if (view.event.transition == GeofenceTransition.ENTER) "entró en" else "salió de"
        return "${view.deviceName} $verb ${view.geofenceName} a las ${TIME.format(view.event.occurredAt.atZone(zone))}"
    }

    private fun json(view: GeofenceEventView, text: String): String = buildJsonObject {
        put("title", "PrivateTracker · $serverName")
        put("message", text)
        put("priority", 7)
        putJsonArray("events") {
            addJsonObject {
                put("device", view.deviceName)
                put("deviceId", view.event.deviceId.value)
                put("geofence", view.geofenceName)
                put("geofenceId", view.event.geofenceId.value)
                put("transition", view.event.transition.name)
                put("occurredAt", view.event.occurredAt.toString())
            }
        }
    }.toString()

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * Geofence alerts on a Linux server: to every open page of the web app and, if the owner set one, to
 * the webhook. With neither, nobody would see them, so crossings are stored as not alerted.
 */
class HostGeofenceAlerts(private val live: LiveAlerts, private val webhook: AlertWebhook?) : GeofenceAlerts {
    private val log = LoggerFactory.getLogger("Geofences")

    override fun canAlert(): Boolean = webhook != null || live.listening

    override fun alert(events: List<GeofenceEventView>) {
        try {
            events.forEach { log.info("{} {} {}", it.deviceName, it.event.transition, it.geofenceName) }
            live.publish(events)
            webhook?.send(events)
        } catch (e: Exception) {
            // The positions behind these alerts are stored; an alert that cannot go out must not change that.
            log.error("Geofence alert failed", e)
        }
    }
}
