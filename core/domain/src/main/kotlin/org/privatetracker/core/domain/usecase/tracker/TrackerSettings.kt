package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.ConnectionCheck
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.validation.TrackerConfigValidator
import java.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Saves the settings form. A server URL typed in by hand that is not one of the paired server's
 * addresses means another server: the pinned key and addresses no longer apply and are dropped.
 */
class UpdateTrackerConfig(private val repository: TrackerConfigRepository) {
    suspend operator fun invoke(config: TrackerConfig): Outcome<TrackerConfig> {
        val normalized = config.copy(
            serverUrl = TrackerConfigValidator.normalizeServerUrl(config.serverUrl),
            deviceName = config.deviceName.trim(),
        )
        val violations = TrackerConfigValidator.validate(normalized)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        return repository.update { current ->
            val sameServer = normalized.serverUrl == current.serverUrl || normalized.serverUrl in current.serverAddresses
            if (sameServer) {
                normalized.copy(serverKey = current.serverKey, serverAddresses = current.serverAddresses)
            } else {
                normalized.copy(serverKey = "", serverAddresses = emptyList())
            }
        }.asSuccess()
    }
}

/** Calls the health endpoint of [serverUrl] and reports latency, compatibility and clock offset. */
class TestServerConnection(
    private val gateway: ServerGateway,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(serverUrl: String): Outcome<ConnectionCheck> {
        TrackerConfigValidator.validateServerUrl(serverUrl)?.let { return DomainError.Validation(listOf(it)).asFailure() }
        val started = timeSource.markNow()
        return gateway.health(TrackerConfigValidator.normalizeServerUrl(serverUrl)).map { info ->
            ConnectionCheck(
                server = info,
                latency = started.elapsedNow().toJavaDuration(),
                compatible = info.protocolVersion == ProtocolVersion.CURRENT,
                clockOffset = Duration.between(clock.now(), info.serverTime),
            )
        }
    }
}

/** The fingerprint of this tracker's key, which the server's owner compares before approving it. Null when the key store fails. */
class GetDeviceKeyFingerprint(
    private val identity: GetOrCreateDeviceIdentity,
    private val keys: DeviceKeys,
) {
    suspend operator fun invoke(): String? = try {
        keyFingerprint(keys.publicKey(identity()))
    } catch (e: DeviceKeyException) {
        null
    }
}
