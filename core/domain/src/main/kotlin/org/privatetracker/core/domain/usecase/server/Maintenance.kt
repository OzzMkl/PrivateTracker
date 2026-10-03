package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import org.privatetracker.core.domain.service.SessionTracker

/** Closes sessions whose device has been silent longer than the online threshold. Returns how many. */
class CloseInactiveSessions(
    private val sessions: SessionRepository,
    private val sessionTracker: SessionTracker,
    private val serverConfig: ServerConfigRepository,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val cutoff = clock.now().minus(serverConfig.get().onlineThreshold)
        return transaction.run {
            val inactive = sessions.findOpenInactiveSince(cutoff)
            inactive.forEach { sessionTracker.close(it, SessionEndReason.INACTIVITY) }
            inactive.size
        }
    }
}

/** Closes every open session because the server is stopping. Returns how many. */
class CloseAllSessions(
    private val sessions: SessionRepository,
    private val sessionTracker: SessionTracker,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.now()
        return transaction.run {
            val open = sessions.findAllOpen()
            open.forEach { sessionTracker.close(it, SessionEndReason.SERVER_STOPPED, at = now) }
            open.size
        }
    }
}

/** Deletes locations older than the retention period, always keeping each device's last location. */
class PurgeExpiredLocations(
    private val locations: LocationRepository,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int =
        locations.deleteReceivedBefore(clock.now().minus(serverConfig.get().retention))
}
