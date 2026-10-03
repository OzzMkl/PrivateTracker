package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.validation.validateDeviceName

/** Creates the device on first contact or refreshes its name and version, and opens or renews its session. */
class RegisterOrUpdateDevice(
    private val devices: DeviceRepository,
    private val serverConfig: ServerConfigRepository,
    private val sessionTracker: SessionTracker,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(registration: DeviceRegistration, remoteAddress: String?): Outcome<RegistrationResult> {
        if (registration.protocolVersion != ProtocolVersion.CURRENT) {
            return DomainError.UnsupportedProtocolVersion(registration.protocolVersion, ProtocolVersion.CURRENT).asFailure()
        }
        validateDeviceName("name", registration.name)?.let { return DomainError.Validation(listOf(it)).asFailure() }

        val config = serverConfig.get()
        val now = clock.now()
        val name = registration.name.trim()
        return transaction.run<Outcome<RegistrationResult>> {
            val existing = devices.get(registration.deviceId)
            if (existing == null) {
                if (!config.acceptNewDevices) return@run DomainError.NewDevicesDisabled.asFailure()
                devices.insert(
                    Device(
                        id = registration.deviceId,
                        name = name,
                        platform = registration.platform,
                        appVersion = registration.appVersion,
                        protocolVersion = registration.protocolVersion,
                        createdAt = now,
                        lastSeenAt = now,
                    ),
                )
            } else {
                devices.update(
                    existing.copy(
                        name = name,
                        platform = registration.platform,
                        appVersion = registration.appVersion,
                        protocolVersion = registration.protocolVersion,
                        lastSeenAt = now,
                    ),
                )
            }
            sessionTracker.recordActivity(
                deviceId = registration.deviceId,
                now = now,
                inactivityLimit = config.onlineThreshold,
                remoteAddress = remoteAddress,
                appVersion = registration.appVersion,
            )
            RegistrationResult(
                deviceId = registration.deviceId,
                created = existing == null,
                maxBatchSize = config.maxBatchSize,
                serverTime = now,
            ).asSuccess()
        }
    }
}
