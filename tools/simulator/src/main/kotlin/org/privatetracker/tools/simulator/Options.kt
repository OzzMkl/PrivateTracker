package org.privatetracker.tools.simulator

import java.nio.file.Path
import java.time.Duration

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Failures injected on the tracker side, each as a probability per upload request. They apply only
 * while positions are generated: the final drain runs on a clean network, so its outcome is the server's.
 */
data class FaultPlan(
    /** The request never leaves the tracker, as when the Wi-Fi drops. */
    val dropRate: Double = 0.0,
    /** The server stores the batch but the answer is lost; the tracker resends and gets duplicates. */
    val lostAckRate: Double = 0.0,
)

/** Waits between upload retries, like WorkManager's exponential backoff, capped at [max]. */
data class RetryPolicy(
    val initial: Duration = Duration.ofSeconds(30),
    val max: Duration = Duration.ofMinutes(5),
) {
    /** [retryAfterSeconds] from the server wins, still capped, so a long Retry-After cannot stall a drain. */
    fun delayFor(consecutiveFailures: Int, retryAfterSeconds: Long?): Duration {
        val wanted = retryAfterSeconds?.let(Duration::ofSeconds)
            ?: initial.multipliedBy(1L shl (consecutiveFailures - 1).coerceIn(0, 16))
        return minOf(wanted, max)
    }
}

data class SimulationOptions(
    val serverUrl: String,
    /** The fingerprint the server screen shows: simulated trackers trust the server by it, as phones set up by hand do. */
    val serverFingerprint: String,
    val trackers: Int = 10,
    val interval: Duration = Duration.ofSeconds(60),
    val duration: Duration = Duration.ofHours(24),
    val batchSize: Int = 50,
    val maxQueueSize: Int = 10_000,
    val faults: FaultPlan = FaultPlan(),
    val retry: RetryPolicy = RetryPolicy(),
    /** After [duration], how long trackers keep uploading what is still queued. */
    val drainTimeout: Duration = Duration.ofMinutes(10),
    val reportEvery: Duration = Duration.ofMinutes(5),
    val center: GeoPoint = GeoPoint(19.4326, -99.1332),
    val outDir: Path,
    /**
     * From 0.6: instead of positions as time goes by, each tracker fills this much past at once,
     * one position per [interval] up to now, and uploads it; for testing the history screen.
     */
    val history: Duration? = null,
)

class UsageException(message: String) : Exception(message)

/** Accepts `500ms`, `30s`, `90m`, `24h` and combinations such as `1h30m`. */
fun parseDuration(raw: String): Duration {
    val text = raw.trim().lowercase()
    val parsed = if (text.endsWith("ms")) {
        text.removeSuffix("ms").toLongOrNull()?.let(Duration::ofMillis)
    } else {
        runCatching { Duration.parse("PT" + text.uppercase()) }.getOrNull()
    }
    if (parsed == null || parsed.isNegative || parsed.isZero) {
        throw UsageException("Duración inválida: $raw (ejemplos: 500ms, 30s, 90m, 24h, 1h30m)")
    }
    return parsed
}

/** `1h 05m 03s`, or milliseconds below one second. */
fun formatDuration(duration: Duration): String {
    if (duration < Duration.ofSeconds(1)) return "${duration.toMillis()} ms"
    val hours = duration.toHours()
    val minutes = duration.toMinutesPart()
    val seconds = duration.toSecondsPart()
    return when {
        hours > 0 -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "${seconds}s"
    }
}
