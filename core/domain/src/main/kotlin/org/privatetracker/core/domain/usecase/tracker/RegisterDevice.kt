package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity

/** Introduces this tracker to the configured server and remembers the limits the server announced. */
class RegisterDevice(
    private val gateway: ServerGateway,
    private val trackerConfig: TrackerConfigRepository,
    private val trackerState: TrackerStateRepository,
    private val identity: GetOrCreateDeviceIdentity,
    private val appInfo: AppInfo,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Outcome<TrackerRegistration> {
        val config = trackerConfig.get()
        if (config.serverUrl.isBlank()) return DomainError.NotConfigured.asFailure()

        val registration = DeviceRegistration(
            deviceId = identity(),
            name = config.deviceName.ifBlank { DEFAULT_DEVICE_NAME },
            platform = appInfo.platform,
            appVersion = appInfo.version,
            protocolVersion = ProtocolVersion.CURRENT,
        )
        return gateway.register(config.serverUrl, registration).map { result ->
            TrackerRegistration(config.serverUrl, result.maxBatchSize, clock.now())
                .also { trackerState.setRegistration(it) }
        }
    }

    private companion object {
        const val DEFAULT_DEVICE_NAME = "Tracker"
    }
}
