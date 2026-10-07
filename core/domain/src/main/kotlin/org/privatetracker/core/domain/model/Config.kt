package org.privatetracker.core.domain.model

import java.time.Duration

enum class LocationPriority { HIGH_ACCURACY, BALANCED, LOW_POWER }

/** Settings of the Tracker mode. Ranges are enforced by [org.privatetracker.core.domain.validation.TrackerConfigValidator]. */
data class TrackerConfig(
    val serverUrl: String = "",
    val deviceName: String = "",
    val intervalSeconds: Int = 60,
    val minDistanceM: Float = 0f,
    val maxAccuracyM: Float = 100f,
    val priority: LocationPriority = LocationPriority.HIGH_ACCURACY,
    val batchSize: Int = 50,
    val maxQueueSize: Int = 10_000,
    val startOnBoot: Boolean = false,
    /** Desired state: when true, tracking is restored after the process dies or the phone reboots. */
    val trackingEnabled: Boolean = false,
    /**
     * The server's key: pinned when pairing with its QR code, or learned on the first contact with a
     * server typed in by hand, once it proved to hold the key of [serverFingerprint].
     */
    val serverKey: String = "",
    /** Every address the QR code listed, [serverUrl] among them, to try when the current one stops answering. */
    val serverAddresses: List<String> = emptyList(),
    /**
     * The fingerprint the server's screen shows, typed in by hand with its address. The tracker trusts
     * the server by it until it has the whole [serverKey]; blank from then on.
     */
    val serverFingerprint: String = "",
    /** From 0.6: while the phone lies still, a fix only every [stillIntervalSeconds], to save battery. */
    val adaptiveInterval: Boolean = true,
    val stillIntervalSeconds: Int = 300,
) {
    /** How often to ask for a fix now; [still] is what the stillness detector says. */
    fun effectiveIntervalSeconds(still: Boolean): Int =
        if (adaptiveInterval && still) maxOf(intervalSeconds, stillIntervalSeconds) else intervalSeconds

    /** What this tracker trusts its server by; with nothing, it talks to no server at all. */
    val serverPin: ServerPin?
        get() = when {
            serverKey.isNotBlank() -> ServerPin.Key(serverKey)
            serverFingerprint.isNotBlank() -> ServerPin.Fingerprint(serverFingerprint)
            else -> null
        }

    /** The fingerprint of the trusted server, however it was pinned, to show and to edit. */
    val pinnedFingerprint: String?
        get() = serverKey.ifBlank { null }?.let(::keyFingerprint) ?: serverFingerprint.ifBlank { null }
}

/** Settings of the Server mode. Ranges are enforced by [org.privatetracker.core.domain.validation.ServerConfigValidator]. */
data class ServerConfig(
    val serverName: String = "PrivateTracker Server",
    val port: Int = 8787,
    val bindAddress: String = "0.0.0.0",
    val autoStart: Boolean = false,
    /** Becomes false in 0.2, when only paired devices get in. */
    val acceptNewDevices: Boolean = true,
    val onlineThresholdSeconds: Int = 300,
    val retentionDays: Int = 30,
    val maxBatchSize: Int = 100,
    /** When false, read endpoints only answer requests from the phone itself. */
    val exposeReadApi: Boolean = false,
    /** Announces the server on the local network (mDNS), so paired trackers find it after its address changes. */
    val advertiseOnLan: Boolean = true,
) {
    val onlineThreshold: Duration get() = Duration.ofSeconds(onlineThresholdSeconds.toLong())
    val retention: Duration get() = Duration.ofDays(retentionDays.toLong())
}
