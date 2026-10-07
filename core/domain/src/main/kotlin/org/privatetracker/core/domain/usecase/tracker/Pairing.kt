package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.result.flatMap
import org.privatetracker.core.common.result.map
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.TrustedEncryptionKey
import org.privatetracker.core.domain.model.encryptionKeyInput
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.ServerDiscovery
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * What a server proved about itself: the identity key it holds, and the encryption key that identity
 * key vouches for. Servers before 0.5 offer no [encryptionKey].
 */
data class VerifiedServer(val publicKey: String, val encryptionKey: EncryptionKey?)

/**
 * Asks the server at [invoke]'s url to sign a fresh challenge and checks the answer against what this
 * tracker trusts it by. Tells this tracker's server apart from any other that took over the address,
 * and gives the server's whole key, which a [ServerPin.Fingerprint] alone does not. The encryption key
 * in the same answer counts only when that key signed it and the challenge's signature names it, so
 * it is the one the server offers now and has not ended by the server's own clock.
 */
class VerifyServerIdentity(
    private val gateway: ServerGateway,
    private val verifier: SignatureVerifier,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend operator fun invoke(serverUrl: String, pin: ServerPin): Outcome<VerifiedServer> {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(CHALLENGE_BYTES).also(random::nextBytes))
        return when (val outcome = gateway.health(serverUrl, pin, challenge)) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> {
                val info = outcome.value
                val key = info.identity?.publicKey?.takeIf(pin::matches)
                val signature = info.identity?.signature?.let(::decodeOrNull)
                val encryption = info.encryptionKey
                val input = serverIdentityInput(challenge, info.serverTime, encryption?.key?.id)
                if (key == null || signature == null || !verifier.verify(key, input, signature)) {
                    return DomainError.ServerIdentityMismatch.asFailure()
                }
                if (encryption == null) return VerifiedServer(key, null).asSuccess()
                // An encryption key the identity key did not sign could be anyone's: nothing gets sealed for it.
                val vouched = encryption.signature.let(::decodeOrNull)?.let { verifier.verify(key, encryptionKeyInput(encryption.key), it) } == true
                when {
                    !vouched -> DomainError.ServerIdentityMismatch.asFailure()
                    // The server never offers a key past its end; one that is would be sealed for nothing.
                    !info.serverTime.isBefore(encryption.key.useUntil) -> DomainError.EncryptionUnavailable.asFailure()
                    else -> VerifiedServer(key, encryption.key).asSuccess()
                }
            }
        }
    }

    private fun decodeOrNull(base64: String): ByteArray? = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()

    private companion object {
        const val CHALLENGE_BYTES = 16
    }
}

/**
 * The key this tracker seals its requests for, from 0.5 on: the one it remembers while that still
 * serves and was vouched for by the server it trusts now; otherwise the server's current one, which
 * the server must sign with the key the tracker trusts. Keys rotate weekly, or when the server's owner
 * says so; [refresh] skips the remembered key after the server said it no longer has it.
 */
class GetServerEncryptionKey(
    private val trackerState: TrackerStateRepository,
    private val verifyServer: VerifyServerIdentity,
    private val clock: Clock,
) {
    suspend operator fun invoke(serverUrl: String, pin: ServerPin, refresh: Boolean = false): Outcome<EncryptionKey> {
        if (!refresh) {
            trackerState.encryptionKey()
                ?.takeIf { pin.matches(it.serverKey) && clock.now().isBefore(it.key.useUntil) }
                ?.let { return it.key.asSuccess() }
        }
        return verifyServer(serverUrl, pin).flatMap { remember(it) }
    }

    /** Keeps the encryption key of a server that just proved its identity; none means a server before 0.5. */
    suspend fun remember(server: VerifiedServer): Outcome<EncryptionKey> {
        val key = server.encryptionKey ?: return DomainError.EncryptionUnavailable.asFailure()
        trackerState.setEncryptionKey(TrustedEncryptionKey(key, server.publicKey))
        return key.asSuccess()
    }

    /** After the server said it no longer has the key: the next request asks for the current one. */
    suspend fun forget() {
        trackerState.setEncryptionKey(null)
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
        var verified: VerifiedServer? = null
        val serverUrl = invite.serverUrls.firstOrNull { url ->
            when (val check = verifyServer(url, pin)) {
                is Outcome.Success -> true.also { verified = check.value }
                is Outcome.Failure -> {
                    lastError = check.error
                    false
                }
            }
        } ?: return lastError.asFailure()

        return registerDevice(PairingTarget(invite, serverUrl, checkNotNull(verified))).map { result ->
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
