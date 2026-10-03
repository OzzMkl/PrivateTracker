package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceDetail
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.DeviceStatus
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Devices with status and last location, most recently seen first. Used by the read API. */
class GetDeviceOverviews(
    private val devices: DeviceRepository,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): List<DeviceOverview> =
        devices.getAllWithLastLocation().toOverviews(clock.now(), serverConfig.get().onlineThreshold)
}

/**
 * Same as [GetDeviceOverviews] as a stream for the UI. Status depends on the clock,
 * so the list is recomputed every [refreshPeriod] even when no data changes.
 */
class ObserveDeviceOverviews(
    private val devices: DeviceRepository,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
    private val refreshPeriod: kotlin.time.Duration = 30.seconds,
) {
    operator fun invoke(): Flow<List<DeviceOverview>> =
        combine(devices.observeAllWithLastLocation(), serverConfig.observe(), ticker()) { rows, config, _ ->
            rows.toOverviews(clock.now(), config.onlineThreshold)
        }.distinctUntilChanged()

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(refreshPeriod)
        }
    }
}

class GetDeviceDetail(
    private val devices: DeviceRepository,
    private val sessions: SessionRepository,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: DeviceId): Outcome<DeviceDetail> {
        val row = devices.getWithLastLocation(id) ?: return DomainError.DeviceNotFound.asFailure()
        val threshold = serverConfig.get().onlineThreshold
        val now = clock.now()
        // An open session whose device went silent is closed lazily; do not present it as current.
        val current = sessions.findOpen(id)?.takeIf { Duration.between(it.lastActivityAt, now) <= threshold }
        return DeviceDetail(row.toOverview(now, threshold), current).asSuccess()
    }
}

internal fun List<DeviceWithLastLocation>.toOverviews(now: Instant, threshold: Duration): List<DeviceOverview> =
    map { it.toOverview(now, threshold) }
        .sortedWith(compareByDescending(nullsFirst(naturalOrder())) { it.device.lastSeenAt })

private fun DeviceWithLastLocation.toOverview(now: Instant, threshold: Duration) =
    DeviceOverview(device, DeviceStatus.of(device.lastSeenAt, now, threshold), lastLocation)
