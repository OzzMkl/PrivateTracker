package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.SignedRequest
import org.privatetracker.core.domain.model.isValidPairingProof
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.validation.validateDeviceName

/**
 * Creates the device on first contact or refreshes its name and version. The request must be signed
 * with the key it registers, which proves the tracker holds it. A new device starts PENDING until the
 * owner approves it; only approved devices open sessions, since a session means accepted activity.
 *
 * A registration from a QR invite carries a [PairingClaim][org.privatetracker.core.domain.model.PairingClaim].
 * The owner showed that QR on purpose, so a valid claim approves the device at once and lets it in
 * even while new devices are refused. It never gives a known id another key: device ids travel in the
 * clear, so anyone holding a ticket could otherwise take over someone else's device.
 */
class RegisterOrUpdateDevice(
    private val devices: DeviceRepository,
    private val serverConfig: ServerConfigRepository,
    private val sessionTracker: SessionTracker,
    private val verifier: SignatureVerifier,
    private val verifySignature: VerifyRequestSignature,
    private val tickets: PairingTicketStore,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        registration: DeviceRegistration,
        request: SignedRequest,
        remoteAddress: String?,
    ): Outcome<RegistrationResult> {
        if (registration.protocolVersion != ProtocolVersion.CURRENT) {
            return DomainError.UnsupportedProtocolVersion(registration.protocolVersion, ProtocolVersion.CURRENT).asFailure()
        }
        validateDeviceName("name", registration.name)?.let { return DomainError.Validation(listOf(it)).asFailure() }
        if (!verifier.isValidKey(registration.publicKey)) {
            return DomainError.Validation("public_key", FieldViolation.INVALID_FORMAT).asFailure()
        }
        if (request.deviceId != registration.deviceId) return DomainError.AuthenticationFailed(AuthFailure.INVALID).asFailure()
        verifySignature(request, registration.publicKey).let { if (it is Outcome.Failure) return it }

        val config = serverConfig.get()
        val now = clock.now()
        val ticket = registration.pairing?.let { claim ->
            tickets.get(claim.ticketId)?.takeIf {
                now.isBefore(it.expiresAt) && (it.usedBy == null || it.usedBy == registration.deviceId) &&
                    isValidPairingProof(it.secret, registration.deviceId, registration.publicKey, claim.proof)
            } ?: return DomainError.PairingInvalid.asFailure()
        }
        val paired = ticket != null
        val name = registration.name.trim()
        return transaction.run<Outcome<RegistrationResult>> {
            val existing = devices.get(registration.deviceId)
            if (ticket != null) {
                // A known id pairs only with the key it already has; a new key is a takeover attempt.
                if (existing != null && existing.publicKey != registration.publicKey) {
                    return@run DomainError.AuthenticationFailed(AuthFailure.KEY_MISMATCH).asFailure()
                }
                // Using the ticket again is only the same phone retrying, and only while the owner still lets it in.
                val retry = ticket.usedBy == registration.deviceId
                if (retry && existing?.approval != DeviceApproval.APPROVED) return@run DomainError.PairingInvalid.asFailure()
                if (!tickets.consume(ticket.id, registration.deviceId)) return@run DomainError.PairingInvalid.asFailure()
            }
            // A first key, for a new device or one from 0.1, is a request to join: same rules for both.
            if (!paired && existing?.publicKey == null) {
                if (!config.acceptNewDevices) return@run DomainError.NewDevicesDisabled.asFailure()
                val waiting = devices.getAllWithLastLocation().count { it.device.awaitsApproval }
                if (waiting >= MAX_PENDING_DEVICES) return@run DomainError.NewDevicesDisabled.asFailure()
            }
            val approval = when {
                existing == null -> {
                    val approval = if (paired) DeviceApproval.APPROVED else DeviceApproval.PENDING
                    devices.insert(
                        Device(
                            id = registration.deviceId,
                            name = name,
                            platform = registration.platform,
                            appVersion = registration.appVersion,
                            protocolVersion = registration.protocolVersion,
                            createdAt = now,
                            lastSeenAt = now,
                            publicKey = registration.publicKey,
                            approval = approval,
                        ),
                    )
                    approval
                }
                !paired && existing.publicKey != null && existing.publicKey != registration.publicKey ->
                    return@run DomainError.AuthenticationFailed(AuthFailure.KEY_MISMATCH).asFailure()
                !paired && existing.approval == DeviceApproval.REJECTED -> return@run DomainError.DeviceRejected.asFailure()
                else -> {
                    // A device from 0.1 gets its first key now, and with it the same approval as a new one.
                    val approval = when {
                        paired -> DeviceApproval.APPROVED
                        existing.publicKey == null -> DeviceApproval.PENDING
                        else -> existing.approval
                    }
                    devices.update(
                        existing.copy(
                            name = name,
                            platform = registration.platform,
                            appVersion = registration.appVersion,
                            protocolVersion = registration.protocolVersion,
                            lastSeenAt = now,
                            publicKey = registration.publicKey,
                            approval = approval,
                        ),
                    )
                    approval
                }
            }
            if (approval == DeviceApproval.APPROVED) {
                sessionTracker.recordActivity(
                    deviceId = registration.deviceId,
                    now = now,
                    inactivityLimit = config.onlineThreshold,
                    remoteAddress = remoteAddress,
                    appVersion = registration.appVersion,
                )
            }
            RegistrationResult(
                deviceId = registration.deviceId,
                created = existing == null,
                maxBatchSize = config.maxBatchSize,
                serverTime = now,
                approval = approval,
            ).asSuccess()
        }
    }

    companion object {
        /** Requests nobody decided on yet; beyond this, anyone on the network could flood the owner's list. */
        const val MAX_PENDING_DEVICES = 50
    }
}
