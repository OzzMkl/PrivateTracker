package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Asks the server at [invoke]'s url to sign a fresh challenge and checks the answer against the key
 * this tracker pinned. Tells this tracker's server apart from any other that took over the address.
 */
class VerifyServerIdentity(
    private val gateway: ServerGateway,
    private val verifier: SignatureVerifier,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend operator fun invoke(serverUrl: String, expectedKey: String): Outcome<ServerInfo> {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(CHALLENGE_BYTES).also(random::nextBytes))
        return when (val outcome = gateway.health(serverUrl, challenge)) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> {
                val info = outcome.value
                val identity = info.identity
                val signature = identity?.let { runCatching { Base64.getDecoder().decode(it.signature) }.getOrNull() }
                val genuine = identity != null && signature != null && identity.publicKey == expectedKey &&
                    verifier.verify(expectedKey, serverIdentityInput(challenge, info.serverTime), signature)
                if (genuine) info.asSuccess() else DomainError.ServerIdentityMismatch.asFailure()
            }
        }
    }

    private companion object {
        const val CHALLENGE_BYTES = 16
    }
}

data class PairingResult(val serverName: String, val serverUrl: String, val approval: DeviceApproval)

/**
 * Pairs this tracker with the server of a QR invite: finds an address where that server answers and
 * proves its key, registers there with proof of the ticket's secret, and only then pins the key and
 * the addresses. A failure leaves the current pairing as it was.
 */
class PairWithServer(
    private val verifyServer: VerifyServerIdentity,
    private val trackerConfig: TrackerConfigRepository,
    private val registerDevice: RegisterDevice,
    private val clock: Clock,
) {
    suspend operator fun invoke(invite: PairingInvite): Outcome<PairingResult> {
        // The server decides; this only spares a trip for a code that is clearly stale, clocks allowing.
        if (clock.now().isAfter(invite.expiresAt.plus(CLOCK_TOLERANCE))) return DomainError.PairingInvalid.asFailure()
        var lastError: DomainError = DomainError.Network.Unreachable
        val serverUrl = invite.serverUrls.firstOrNull { url ->
            when (val check = verifyServer(url, invite.serverKey)) {
                is Outcome.Success -> true
                is Outcome.Failure -> {
                    lastError = check.error
                    false
                }
            }
        } ?: return lastError.asFailure()

        return registerDevice(PairingTarget(invite, serverUrl)).map { result ->
            trackerConfig.update { it.copy(serverUrl = serverUrl, serverKey = invite.serverKey, serverAddresses = invite.serverUrls) }
            PairingResult(invite.serverName, serverUrl, result.approval)
        }
    }

    private companion object {
        val CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}

/** The server this tracker sends to now, which pairing with another one would replace. */
data class CurrentServer(val url: String, val keyFingerprint: String?)

class GetCurrentServer(private val trackerConfig: TrackerConfigRepository) {
    suspend operator fun invoke(): CurrentServer? {
        val config = trackerConfig.get()
        if (config.serverUrl.isBlank()) return null
        return CurrentServer(config.serverUrl, config.serverKey.ifBlank { null }?.let(::keyFingerprint))
    }
}

/**
 * After the server stopped answering at its address, looks for it at the other addresses its QR
 * code listed and switches to the first one where it proves its key. True when it switched.
 */
class FailOverServerAddress(
    private val trackerConfig: TrackerConfigRepository,
    private val verifyServer: VerifyServerIdentity,
) {
    suspend operator fun invoke(): Boolean {
        val config = trackerConfig.get()
        if (config.serverKey.isBlank()) return false
        val found = config.serverAddresses
            .filter { it != config.serverUrl }
            .firstOrNull { verifyServer(it, config.serverKey) is Outcome.Success }
            ?: return false
        trackerConfig.update { it.copy(serverUrl = found) }
        return true
    }
}
