package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.PairingClaim
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.model.pairingProof
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity

/**
 * Introduces this tracker and its public key to the configured server and remembers the limits the
 * server announced. Registering again is harmless; a new device then waits for the owner's approval,
 * unless it registers from a QR [pairing] invite. With a pinned server key, the server must prove it
 * holds that key first, so this tracker never introduces itself to a server that took over the address.
 */
class RegisterDevice(
    private val gateway: ServerGateway,
    private val trackerConfig: TrackerConfigRepository,
    private val trackerState: TrackerStateRepository,
    private val identity: GetOrCreateDeviceIdentity,
    private val keys: DeviceKeys,
    private val verifyServer: VerifyServerIdentity,
    private val appInfo: AppInfo,
    private val clock: Clock,
) {
    /**
     * Registers with the configured server, or with [pairing]'s chosen address while pairing: the
     * configuration then changes only once the server has accepted this tracker.
     */
    suspend operator fun invoke(pairing: PairingTarget? = null): Outcome<RegistrationOutcome> {
        val config = trackerConfig.get()
        val serverUrl = pairing?.serverUrl ?: config.serverUrl
        if (serverUrl.isBlank()) return DomainError.NotConfigured.asFailure()
        // Pairing has just checked the server itself.
        if (pairing == null && config.serverKey.isNotBlank()) {
            verifyServer(serverUrl, config.serverKey).let { if (it is Outcome.Failure) return it }
        }

        val deviceId = identity()
        val publicKey = try {
            keys.publicKey(deviceId)
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        val registration = DeviceRegistration(
            deviceId = deviceId,
            name = config.deviceName.ifBlank { DEFAULT_DEVICE_NAME },
            platform = appInfo.platform,
            appVersion = appInfo.version,
            protocolVersion = ProtocolVersion.CURRENT,
            publicKey = publicKey,
            pairing = pairing?.invite?.let { PairingClaim(it.ticketId, pairingProof(it.secret, deviceId, publicKey)) },
        )
        return gateway.register(serverUrl, registration).map { result ->
            val stored = TrackerRegistration(serverUrl, result.maxBatchSize, clock.now())
            trackerState.setRegistration(stored)
            RegistrationOutcome(stored, result.approval)
        }
    }

    private companion object {
        const val DEFAULT_DEVICE_NAME = "Tracker"
    }
}

/** What a registration left stored, and whether the server already lets this tracker in. */
data class RegistrationOutcome(val registration: TrackerRegistration, val approval: DeviceApproval)

/** Where a pairing registers: the invite and the address where its server proved its key. */
data class PairingTarget(val invite: PairingInvite, val serverUrl: String)
