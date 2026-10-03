package org.privatetracker.core.domain.service

import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceSession
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.model.SessionId
import org.privatetracker.core.domain.repository.SessionRepository
import java.time.Duration
import java.time.Instant

/** Keeps one open session per device, shared by registration and ingest. Call it inside a transaction. */
class SessionTracker(
    private val sessions: SessionRepository,
    private val ids: IdGenerator,
) {
    /**
     * Records activity of [deviceId] at [now]. The open session is reused if the device was active
     * within [inactivityLimit]; otherwise it is closed as [SessionEndReason.INACTIVITY] and a new one starts.
     */
    suspend fun recordActivity(
        deviceId: DeviceId,
        now: Instant,
        inactivityLimit: Duration,
        remoteAddress: String?,
        appVersion: String?,
    ): DeviceSession {
        val open = sessions.findOpen(deviceId)
        if (open != null && Duration.between(open.lastActivityAt, now) <= inactivityLimit) {
            val renewed = open.copy(
                lastActivityAt = maxOf(open.lastActivityAt, now),
                remoteAddress = remoteAddress ?: open.remoteAddress,
                appVersion = appVersion ?: open.appVersion,
            )
            sessions.update(renewed)
            return renewed
        }
        if (open != null) close(open, SessionEndReason.INACTIVITY)
        val started = DeviceSession(
            id = SessionId.of(ids.newId()),
            deviceId = deviceId,
            startedAt = now,
            lastActivityAt = now,
            remoteAddress = remoteAddress,
            appVersion = appVersion,
        )
        sessions.insert(started)
        return started
    }

    /** A session ends when its device was last active, not when someone noticed the silence. */
    suspend fun close(session: DeviceSession, reason: SessionEndReason, at: Instant = session.lastActivityAt) {
        sessions.update(session.copy(endedAt = at, endReason = reason))
    }
}
