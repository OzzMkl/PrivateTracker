package org.privatetracker.core.domain.model

import java.time.Instant

enum class SessionEndReason { INACTIVITY, SERVER_STOPPED, DEVICE_REMOVED }

/** A continuous period of activity of one device. A device has at most one open session. */
data class DeviceSession(
    val id: SessionId,
    val deviceId: DeviceId,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val endedAt: Instant? = null,
    val endReason: SessionEndReason? = null,
    val remoteAddress: String? = null,
    val appVersion: String? = null,
    val locationsReceived: Int = 0,
) {
    val isOpen: Boolean get() = endedAt == null
}
