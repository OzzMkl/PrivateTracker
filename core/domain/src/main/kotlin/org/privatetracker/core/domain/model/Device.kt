package org.privatetracker.core.domain.model

import java.time.Duration
import java.time.Instant

object ProtocolVersion {
    /** Version of the tracker-server protocol this build speaks. */
    const val CURRENT = 1
}

enum class Platform {
    ANDROID, LINUX, WEB, OTHER;

    companion object {
        fun parse(raw: String): Platform = entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

/** A tracker as seen by the server. */
data class Device(
    val id: DeviceId,
    val name: String,
    val platform: Platform,
    val appVersion: String?,
    val protocolVersion: Int,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
    /** Null in 0.1; holds the device key from 0.2 on. */
    val publicKey: String? = null,
) {
    companion object {
        const val NAME_MAX_LENGTH = 64
    }
}

/** Derived from [Device.lastSeenAt]; never stored. */
enum class DeviceStatus {
    ONLINE, STALE, OFFLINE;

    companion object {
        /** A device stays STALE until its silence reaches this many online thresholds. */
        const val STALE_FACTOR = 3L

        fun of(lastSeenAt: Instant?, now: Instant, onlineThreshold: Duration): DeviceStatus {
            if (lastSeenAt == null) return OFFLINE
            val silence = Duration.between(lastSeenAt, now)
            return when {
                silence <= onlineThreshold -> ONLINE
                silence <= onlineThreshold.multipliedBy(STALE_FACTOR) -> STALE
                else -> OFFLINE
            }
        }
    }
}

data class DeviceWithLastLocation(val device: Device, val lastLocation: Location?)

data class DeviceOverview(val device: Device, val status: DeviceStatus, val lastLocation: Location?)

data class DeviceDetail(val overview: DeviceOverview, val currentSession: DeviceSession?)
