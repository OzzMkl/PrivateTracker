package org.privatetracker.core.domain.model

import org.privatetracker.core.common.result.DomainError
import java.time.Duration
import java.time.Instant

/** What a tracker tells a server about itself when it registers. */
data class DeviceRegistration(
    val deviceId: DeviceId,
    val name: String,
    val platform: Platform,
    val appVersion: String?,
    val protocolVersion: Int,
)

data class RegistrationResult(
    val deviceId: DeviceId,
    val created: Boolean,
    val maxBatchSize: Int,
    val serverTime: Instant,
)

/** What the tracker remembers about its registration with a server. */
data class TrackerRegistration(
    val serverUrl: String,
    val maxBatchSize: Int,
    val registeredAt: Instant,
)

/** Answer of the server health endpoint. */
data class ServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: Int,
    val serverTime: Instant,
)

data class ConnectionCheck(
    val server: ServerInfo,
    val latency: Duration,
    val compatible: Boolean,
    /** Server time minus tracker time; large values mean one of the clocks is wrong. */
    val clockOffset: Duration,
)

/** Name and version of this build, provided by the platform layer. */
data class AppInfo(val platform: Platform, val version: String)

sealed interface UploadResult {
    /** The batches went through. [hasMore] is true when the run stopped at its batch limit. */
    data class Completed(val sent: Int, val rejected: Int, val hasMore: Boolean) : UploadResult

    /** A transient failure; the outbox is intact and the upload should be retried later. */
    data class RetryLater(val error: DomainError, val retryAfterSeconds: Long? = null) : UploadResult

    /** Retrying will not help until something changes (configuration, server, app version). */
    data class Blocked(val error: DomainError) : UploadResult
}

sealed interface RecordResult {
    data class Recorded(val location: Location, val droppedOldest: Int) : RecordResult
    data class Skipped(val reason: SkipReason) : RecordResult
}

enum class SkipReason { LOW_ACCURACY, TOO_CLOSE, INVALID }
