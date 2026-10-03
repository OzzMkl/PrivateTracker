package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.SignedRequest
import org.privatetracker.core.domain.port.NonceRegistry
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.repository.DeviceRepository
import java.time.Duration

/**
 * Checks that a request is recent, signed by the holder of a given public key, and not seen before.
 * The nonce is recorded only once the signature verifies, so nobody can use up a real device's nonces.
 */
class VerifyRequestSignature(
    private val verifier: SignatureVerifier,
    private val nonces: NonceRegistry,
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
) {
    operator fun invoke(request: SignedRequest, publicKey: String): Outcome<Unit> {
        if (Duration.between(request.signedAt, clock.now()).abs() > maxAge) return failure(AuthFailure.EXPIRED)
        if (!verifier.verify(publicKey, request.signingInput, request.signature)) return failure(AuthFailure.INVALID)
        // Past signedAt + maxAge the request is refused as expired, so the nonce can be forgotten then.
        if (!nonces.register(request.deviceId, request.nonce, request.signedAt.plus(maxAge))) return failure(AuthFailure.REPLAYED)
        return Unit.asSuccess()
    }

    private fun failure(reason: AuthFailure) = DomainError.AuthenticationFailed(reason).asFailure()

    companion object {
        /** Same tolerance as for location timestamps: clocks of phones on automatic time agree far better. */
        val MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}

/**
 * Attributes a request to an approved device: the device exists, the request carries its key's
 * signature, and the owner approved it. The signature is checked before the approval, so a caller
 * without the key learns nothing about a device's state.
 */
class AuthenticateDevice(
    private val devices: DeviceRepository,
    private val verifySignature: VerifyRequestSignature,
) {
    suspend operator fun invoke(request: SignedRequest): Outcome<Device> {
        val device = devices.get(request.deviceId) ?: return DomainError.DeviceNotRegistered.asFailure()
        // Registered by 0.1, without a key: the tracker registers again and binds one.
        val key = device.publicKey ?: return DomainError.DeviceNotRegistered.asFailure()
        verifySignature(request, key).let { if (it is Outcome.Failure) return it }
        return when (device.approval) {
            DeviceApproval.PENDING -> DomainError.DevicePendingApproval.asFailure()
            DeviceApproval.REJECTED -> DomainError.DeviceRejected.asFailure()
            DeviceApproval.APPROVED -> device.asSuccess()
        }
    }
}
