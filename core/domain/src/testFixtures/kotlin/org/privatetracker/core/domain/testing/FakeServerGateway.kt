package org.privatetracker.core.domain.testing

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerIdentity
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.SignedEncryptionKey
import org.privatetracker.core.domain.model.encryptionKeyInput
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.ServerGateway
import java.time.Duration
import java.util.Base64

/**
 * Scriptable server. Queued responses are used first, in order; when a queue is empty
 * the gateway behaves like a healthy server that accepts everything. Every call first goes through
 * what TLS does: a server whose key the pin does not name is refused before anything is sent. Device
 * routes then check what the real server checks of a sealed body: that it still holds the key.
 */
class FakeServerGateway(private val clock: Clock = FakeClock()) : ServerGateway {
    val healthResponses = ArrayDeque<Outcome<ServerInfo>>()
    val registerResponses = ArrayDeque<Outcome<RegistrationResult>>()
    val uploadResponses = ArrayDeque<(List<Location>) -> Outcome<LocationBatchResult>>()

    val registrations = mutableListOf<DeviceRegistration>()
    val uploads = mutableListOf<List<Location>>()
    var maxBatchSize = 100
    var approval = DeviceApproval.APPROVED

    /** Health calls in order, with the challenge each carried. */
    val healthCalls = mutableListOf<Pair<String, String?>>()

    /** The key of the server at every address, answering a challenge as [FakeSignatureVerifier] expects. */
    var serverKey: String = FakeServerKeys.SERVER_KEY

    /** Another server at some addresses, with its own key. */
    val keysByUrl = mutableMapOf<String, String>()

    /** Addresses where nothing answers. */
    val unreachable = mutableSetOf<String>()

    /** The pin of every call, in order. */
    val pins = mutableListOf<ServerPin>()

    /** The pin each upload carried. */
    val uploadPins = mutableListOf<ServerPin>()

    /** What health offers to seal for, signed with the server's key; null for a server before 0.5. */
    var encryptionKey: EncryptionKey? = anEncryptionKey(1)

    /** Who signs [encryptionKey]: the server itself unless a test says otherwise. */
    var encryptionKeySigner: String? = null

    /** What an attacker on the way puts in health's answer instead of [encryptionKey], the challenge's signature untouched. */
    var swappedEncryptionKey: EncryptionKey? = null

    /** Ids of the keys the server can still open. */
    val keptEncryptionKeys = mutableSetOf(anEncryptionKey(1).id)

    /** The encryption key each register and upload was sealed for, in order. */
    val sealedFor = mutableListOf<String>()

    /** Offers key [number] from now on; [dropOthers] deletes every older key, as "rotate now" does. */
    fun rotateEncryptionKey(number: Int, dropOthers: Boolean) {
        val key = anEncryptionKey(number)
        encryptionKey = key
        if (dropOthers) keptEncryptionKeys.clear()
        keptEncryptionKeys += key.id
    }

    override suspend fun health(serverUrl: String, pin: ServerPin, challenge: String?): Outcome<ServerInfo> {
        healthCalls += serverUrl to challenge
        handshake(serverUrl, pin)?.let { return Outcome.Failure(it) }
        healthResponses.removeFirstOrNull()?.let { return it }
        val now = clock.now()
        val identity = challenge?.let {
            val key = keyAt(serverUrl)
            ServerIdentity(key, Base64.getEncoder().encodeToString(fakeSignature(key, serverIdentityInput(it, now, encryptionKey?.id))))
        }
        val encryption = (swappedEncryptionKey ?: encryptionKey)?.let {
            SignedEncryptionKey(it, Base64.getEncoder().encodeToString(fakeSignature(encryptionKeySigner ?: keyAt(serverUrl), encryptionKeyInput(it))))
        }
        return Outcome.Success(ServerInfo("Fake", "0.1.0", ProtocolVersion.CURRENT, now, identity, encryption))
    }

    override suspend fun register(
        serverUrl: String,
        pin: ServerPin,
        registration: DeviceRegistration,
        encryptionKey: EncryptionKey,
    ): Outcome<RegistrationResult> {
        handshake(serverUrl, pin)?.let { return Outcome.Failure(it) }
        unseal(encryptionKey)?.let { return Outcome.Failure(it) }
        registrations += registration
        return registerResponses.removeFirstOrNull()
            ?: Outcome.Success(RegistrationResult(registration.deviceId, registrations.size == 1, maxBatchSize, clock.now(), approval))
    }

    override suspend fun uploadLocations(
        serverUrl: String,
        pin: ServerPin,
        deviceId: DeviceId,
        locations: List<Location>,
        encryptionKey: EncryptionKey,
    ): Outcome<LocationBatchResult> {
        handshake(serverUrl, pin)?.let { return Outcome.Failure(it) }
        unseal(encryptionKey)?.let { return Outcome.Failure(it) }
        uploadPins += pin
        uploads += locations
        val scripted = uploadResponses.removeFirstOrNull()
        return scripted?.invoke(locations) ?: acceptAll(locations)
    }

    fun acceptAll(locations: List<Location>): Outcome<LocationBatchResult> =
        Outcome.Success(LocationBatchResult(locations.map { it.id }, emptyList(), emptyList(), clock.now()))

    private fun keyAt(serverUrl: String): String = keysByUrl[serverUrl] ?: serverKey

    private fun unseal(key: EncryptionKey): DomainError? {
        sealedFor += key.id
        return if (key.id in keptEncryptionKeys) null else DomainError.EncryptionKeyUnknown
    }

    private fun handshake(serverUrl: String, pin: ServerPin): DomainError? {
        pins += pin
        return when {
            serverUrl in unreachable -> DomainError.Network.Unreachable
            !pin.matches(keyAt(serverUrl)) -> DomainError.ServerIdentityMismatch
            else -> null
        }
    }
}

/** Encryption key [number] of the fake server, made at [T0] plus that many weeks and used for one more. */
fun anEncryptionKey(number: Int): EncryptionKey =
    EncryptionKey("ENC-$number", fakeEncryptionKey(number), T0.plus(Duration.ofDays(7L * number)))
