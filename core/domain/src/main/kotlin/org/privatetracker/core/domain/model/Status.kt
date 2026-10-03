package org.privatetracker.core.domain.model

import org.privatetracker.core.common.result.DomainError
import java.time.Instant

/** What the tracking service reports while it runs. Lost with the process, unlike [TrackerConfig.trackingEnabled]. */
data class TrackerActivity(
    val running: Boolean = false,
    val lastFix: LocationFix? = null,
    val lastRecord: RecordResult? = null,
    val lastUpload: UploadResult? = null,
    val lastUploadAt: Instant? = null,
    /** Why the service stopped on its own, such as a revoked permission. */
    val error: DomainError? = null,
)

/** Everything the tracker screen shows. */
data class TrackingStatus(
    val config: TrackerConfig,
    val activity: TrackerActivity,
    val queueSize: Int,
) {
    val enabled: Boolean get() = config.trackingEnabled
    val running: Boolean get() = activity.running

    /** A full queue drops its oldest positions, so the UI warns before that happens. */
    val queueNearlyFull: Boolean get() = queueSize >= config.maxQueueSize * QUEUE_WARNING_FRACTION

    companion object {
        const val QUEUE_WARNING_FRACTION = 0.8
    }
}

sealed interface ServerRunState {
    data object Stopped : ServerRunState
    data object Starting : ServerRunState
    data class Running(val port: Int) : ServerRunState

    /** Draining: requests get 503 for the grace period, so trackers keep their outbox. */
    data object Stopping : ServerRunState
    data class Failed(val error: DomainError) : ServerRunState
}

/** What the server service reports. [requestsServed] counts since the server last started. */
data class ServerActivity(
    val state: ServerRunState = ServerRunState.Stopped,
    val requestsServed: Long = 0,
)

/** An IP address of this phone, as the platform lists it. */
data class NetworkAddress(val interfaceName: String, val host: String)

/** Declared in the order the server screen lists addresses. */
enum class AddressKind { LAN, VPN, OTHER, LOOPBACK }

/** A URL under which trackers can reach this server. */
data class ServerAddress(val url: String, val kind: AddressKind)

/** Everything the server screen shows. [addresses] lists the most useful first and is empty while stopped. */
data class ServerStatus(
    val activity: ServerActivity,
    val config: ServerConfig,
    val addresses: List<ServerAddress>,
) {
    val state: ServerRunState get() = activity.state
}
