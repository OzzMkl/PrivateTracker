package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.ServerDiscovery
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Asks the server at [invoke]'s url to sign a fresh challenge and checks the answer against what this
 * tracker trusts it by. Tells this tracker's server apart from any other that took over the address,
 * and gives the server's whole key, which a [ServerPin.Fingerprint] alone does not.
 */
class VerifyServerIdentity(
    private val gateway: ServerGateway,
    private val verifier: SignatureVerifier,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The key the server proved to hold. */
    suspend operator fun invoke(serverUrl: String, pin: ServerPin): Outcome<String> {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(CHALLENGE_BYTES).also(random::nextBytes))
        return when (val outcome = gateway.health(serverUrl, pin, challenge)) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> {
                val info = outcome.value
                val key = info.identity?.publicKey?.takeIf(pin::matches)
                val signature = info.identity?.let { runCatching { Base64.getDecoder().decode(it.signature) }.getOrNull() }
                if (key != null && signature != null && verifier.verify(key, serverIdentityInput(challenge, info.serverTime), signature)) {
                    key.asSuccess()
                } else {
                    DomainError.ServerIdentityMismatch.asFailure()
                }
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
        val pin = ServerPin.Key(invite.serverKey)
        val serverUrl = invite.serverUrls.firstOrNull { url ->
            when (val check = verifyServer(url, pin)) {
                is Outcome.Success -> true
                is Outcome.Failure -> {
                    lastError = check.error
                    false
                }
            }
        } ?: return lastError.asFailure()

        return registerDevice(PairingTarget(invite, serverUrl)).map { result ->
            trackerConfig.update {
                it.copy(serverUrl = serverUrl, serverKey = invite.serverKey, serverAddresses = invite.serverUrls, serverFingerprint = "")
            }
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
        return CurrentServer(config.serverUrl, config.pinnedFingerprint)
    }
}

/**
 * After the server stopped answering at its address, looks for it at the other addresses its QR code
 * listed and then on the local network, and switches to the first place where it proves its key. A
 * server found by discovery joins the known addresses. True when it switched.
 *
 * Discovery listens on the network for seconds, so it runs at most once per [discoveryInterval]: a
 * server that is simply off must not cost a scan at every upload. Keep one instance per process.
 */
class FailOverServerAddress(
    private val trackerConfig: TrackerConfigRepository,
    private val verifyServer: VerifyServerIdentity,
    private val discovery: ServerDiscovery,
    private val clock: Clock,
    private val discoveryInterval: Duration = DISCOVERY_INTERVAL,
) {
    private var lastDiscovery: Instant? = null

    suspend operator fun invoke(): Boolean {
        val config = trackerConfig.get()
        val pin = config.serverPin ?: return false
        val known = config.serverAddresses.filter { it != config.serverUrl }
        known.firstOrNull { verifyServer(it, pin) is Outcome.Success }?.let { found ->
            return switchTo(found, pin, remember = false)
        }

        val now = clock.now()
        if (lastDiscovery?.let { Duration.between(it, now) < discoveryInterval } == true) return false
        lastDiscovery = now
        val hint = config.pinnedFingerprint?.replace("-", "")
        val found = discovery.discover(DISCOVERY_TIMEOUT)
            .filter { (it.keyHint == null || it.keyHint == hint) && it.url != config.serverUrl && it.url !in known }
            .map { it.url }
            .distinct()
            .firstOrNull { verifyServer(it, pin) is Outcome.Success }
            ?: return false
        return switchTo(found, pin, remember = true)
    }

    /** Unless the settings moved on to another server while this looked, which then stays as saved. */
    private suspend fun switchTo(url: String, pin: ServerPin, remember: Boolean): Boolean {
        var switched = false
        trackerConfig.update { current ->
            if (current.serverPin != pin) return@update current
            switched = true
            val addresses = if (remember) (listOf(url) + current.serverAddresses).distinct().take(MAX_ADDRESSES) else current.serverAddresses
            current.copy(serverUrl = url, serverAddresses = addresses)
        }
        return switched
    }

    companion object {
        val DISCOVERY_INTERVAL: Duration = Duration.ofMinutes(5)
        val DISCOVERY_TIMEOUT: Duration = Duration.ofSeconds(8)
        private const val MAX_ADDRESSES = 8
    }
}
