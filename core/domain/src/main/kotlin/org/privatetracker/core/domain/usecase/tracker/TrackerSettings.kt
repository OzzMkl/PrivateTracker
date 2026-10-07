package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.ConnectionCheck
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.normalizeFingerprint
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
 * Saves the settings form. The fingerprint, not the address, says which server this is: with the
 * fingerprint of the key already pinned, a new address is the same server that moved, and the key and
 * the known addresses stay. Any other fingerprint is another server, trusted by that fingerprint until
 * the first contact gives its whole key.
 */
class UpdateTrackerConfig(private val repository: TrackerConfigRepository) {
    suspend operator fun invoke(config: TrackerConfig): Outcome<TrackerConfig> {
        val normalized = config.copy(
            serverUrl = TrackerConfigValidator.normalizeServerUrl(config.serverUrl),
            deviceName = config.deviceName.trim(),
            serverFingerprint = normalizeFingerprint(config.serverFingerprint),
            // Only pairing and the server itself set the key; the form names the server by its fingerprint.
            serverKey = "",
            serverAddresses = emptyList(),
        )
        val violations = TrackerConfigValidator.validate(normalized)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        return repository.update { current ->
            val sameServer = current.serverKey.isNotBlank() && keyFingerprint(current.serverKey) == normalized.serverFingerprint
            if (sameServer) {
                normalized.copy(serverKey = current.serverKey, serverAddresses = current.serverAddresses, serverFingerprint = "")
            } else {
                normalized
            }
        }.asSuccess()
    }
}

/**
 * Calls the health endpoint of [serverUrl] over TLS, trusting only the server with [fingerprint], and
 * reports latency, compatibility and clock offset. A server with another key fails the handshake.
 */
class TestServerConnection(
    private val gateway: ServerGateway,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(serverUrl: String, fingerprint: String): Outcome<ConnectionCheck> {
        val url = TrackerConfigValidator.normalizeServerUrl(serverUrl)
        val normalizedFingerprint = normalizeFingerprint(fingerprint)
        val violations = listOfNotNull(
            TrackerConfigValidator.validateServerUrl(url),
            TrackerConfigValidator.validateServerFingerprint(normalizedFingerprint),
        )
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        val started = timeSource.markNow()
        return gateway.health(url, ServerPin.Fingerprint(normalizedFingerprint)).map { info ->
            ConnectionCheck(
                server = info,
                latency = started.elapsedNow().toJavaDuration(),
                // From 0.5 a server must also offer end-to-end encryption, or this tracker sends it nothing.
                compatible = info.protocolVersion == ProtocolVersion.CURRENT && info.encryptionKey != null,
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
