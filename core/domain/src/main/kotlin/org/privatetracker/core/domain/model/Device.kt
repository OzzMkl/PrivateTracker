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

/** Whether the server's owner lets a device in. Only APPROVED devices can send locations. */
enum class DeviceApproval { PENDING, APPROVED, REJECTED }

/** A tracker as seen by the server. */
data class Device(
    val id: DeviceId,
    val name: String,
    val platform: Platform,
    val appVersion: String?,
    val protocolVersion: Int,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
    /** ECDSA P-256 key the device signs with, as Base64 X.509 SubjectPublicKeyInfo. Null for devices that 0.1 registered. */
    val publicKey: String? = null,
    val approval: DeviceApproval = DeviceApproval.PENDING,
    /**
     * When the device last changed what it is (name, version, key) or the owner changed its approval,
     * on whichever paired server it happened (1.0): the latest change wins on all of them. Null for
     * devices from before 1.0, which count as changed when they were created.
     */
    val updatedAt: Instant? = null,
    /**
     * When the owner last decided on it (approved, rejected), on whichever paired server (1.0). Apart
     * from [updatedAt], so a tracker checking in with a new version never undoes a decision made
     * meanwhile on another server. Null before 1.0.
     */
    val approvalChangedAt: Instant? = null,
) {
    /** For comparing changes to the device's details between paired servers. */
    val changedAt: Instant get() = updatedAt ?: createdAt

    /** For comparing the owner's decisions between paired servers. */
    val approvalDecidedAt: Instant get() = approvalChangedAt ?: changedAt

    /** The same name, platform, versions and key: what the tracker itself reports. */
    fun sameDetailsAs(other: Device): Boolean =
        name == other.name && platform == other.platform && appVersion == other.appVersion &&
            protocolVersion == other.protocolVersion && publicKey == other.publicKey

    /**
     * Waiting for the owner's decision. A device without a key (registered by 0.1) is not: approving it
     * would mean nothing, since its next registration brings a key and starts the approval over.
     */
    val awaitsApproval: Boolean get() = approval == DeviceApproval.PENDING && publicKey != null

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

data class DeviceDetail(
    val overview: DeviceOverview,
    val currentSession: DeviceSession?,
    /** Newest first, the current one included. Only the app fills it; the read API leaves it empty. */
    val recentSessions: List<DeviceSession> = emptyList(),
)
