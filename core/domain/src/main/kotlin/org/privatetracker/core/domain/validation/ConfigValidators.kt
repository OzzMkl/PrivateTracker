package org.privatetracker.core.domain.validation

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.FieldViolation.Companion.HTTPS_REQUIRED
import org.privatetracker.core.common.result.FieldViolation.Companion.INVALID_FORMAT
import org.privatetracker.core.common.result.FieldViolation.Companion.OUT_OF_RANGE
import org.privatetracker.core.common.result.FieldViolation.Companion.REQUIRED
import org.privatetracker.core.common.result.FieldViolation.Companion.TOO_LONG
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.isValidFingerprint
import java.net.URI

/** Hard limit of positions per request, shared by tracker and server. */
const val MAX_BATCH_SIZE = 100

object TrackerConfigValidator {
    val INTERVAL_SECONDS = 10..3600
    val MIN_DISTANCE_M = 0f..1000f
    val MAX_ACCURACY_M = 1f..5000f
    val BATCH_SIZE = 1..MAX_BATCH_SIZE
    val MAX_QUEUE_SIZE = 100..50_000

    fun validate(config: TrackerConfig): List<FieldViolation> = buildList {
        validateServerUrl(config.serverUrl)?.let(::add)
        // A key pinned from a QR code or learned already is all the trust needed.
        if (config.serverKey.isBlank()) validateServerFingerprint(config.serverFingerprint)?.let(::add)
        validateDeviceName("deviceName", config.deviceName)?.let(::add)
        if (config.intervalSeconds !in INTERVAL_SECONDS) add(FieldViolation("intervalSeconds", OUT_OF_RANGE))
        if (config.minDistanceM !in MIN_DISTANCE_M) add(FieldViolation("minDistanceM", OUT_OF_RANGE))
        if (config.maxAccuracyM !in MAX_ACCURACY_M) add(FieldViolation("maxAccuracyM", OUT_OF_RANGE))
        if (config.batchSize !in BATCH_SIZE) add(FieldViolation("batchSize", OUT_OF_RANGE))
        if (config.maxQueueSize !in MAX_QUEUE_SIZE) add(FieldViolation("maxQueueSize", OUT_OF_RANGE))
    }

    /** Accepts `https://host[:port]` with an optional trailing slash and nothing else: from 0.4 servers only speak TLS. */
    fun validateServerUrl(raw: String): FieldViolation? {
        if (raw.isBlank()) return FieldViolation("serverUrl", REQUIRED)
        val invalid = FieldViolation("serverUrl", INVALID_FORMAT)
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return invalid
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http") return FieldViolation("serverUrl", HTTPS_REQUIRED)
        if (scheme != "https") return invalid
        if (uri.host.isNullOrBlank()) return invalid
        if (uri.port != -1 && uri.port !in 1..65535) return invalid
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return invalid
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return invalid
        return null
    }

    /** Trims, drops a trailing slash and adds `https://` when no scheme was typed. */
    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        return if (trimmed.isEmpty() || "://" in trimmed) trimmed else "https://$trimmed"
    }

    /** Expects the form [org.privatetracker.core.domain.model.normalizeFingerprint] gives. */
    fun validateServerFingerprint(value: String): FieldViolation? = when {
        value.isBlank() -> FieldViolation("serverFingerprint", REQUIRED)
        !isValidFingerprint(value) -> FieldViolation("serverFingerprint", INVALID_FORMAT)
        else -> null
    }
}

object ServerConfigValidator {
    val PORT = 1024..65535
    val ONLINE_THRESHOLD_SECONDS = 30..3600
    val RETENTION_DAYS = 1..365
    val BATCH_SIZE = 1..MAX_BATCH_SIZE

    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val IPV6 = Regex("^[0-9a-fA-F:]+$")

    fun validate(config: ServerConfig): List<FieldViolation> = buildList {
        validateDeviceName("serverName", config.serverName)?.let(::add)
        if (config.port !in PORT) add(FieldViolation("port", OUT_OF_RANGE))
        if (!isIpLiteral(config.bindAddress)) add(FieldViolation("bindAddress", INVALID_FORMAT))
        if (config.onlineThresholdSeconds !in ONLINE_THRESHOLD_SECONDS) {
            add(FieldViolation("onlineThresholdSeconds", OUT_OF_RANGE))
        }
        if (config.retentionDays !in RETENTION_DAYS) add(FieldViolation("retentionDays", OUT_OF_RANGE))
        if (config.maxBatchSize !in BATCH_SIZE) add(FieldViolation("maxBatchSize", OUT_OF_RANGE))
    }

    private fun isIpLiteral(raw: String): Boolean =
        IPV4.matches(raw) || (raw.contains(':') && IPV6.matches(raw))
}

/** Names shown in the UI: trimmed, not blank, at most [Device.NAME_MAX_LENGTH] characters. */
fun validateDeviceName(field: String, name: String): FieldViolation? = when {
    name.isBlank() -> FieldViolation(field, REQUIRED)
    name.trim().length > Device.NAME_MAX_LENGTH -> FieldViolation(field, TOO_LONG)
    else -> null
}
