package org.privatetracker.core.domain.testing

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerIdentity
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.ServerGateway
import java.util.Base64

/**
 * Scriptable server. Queued responses are used first, in order; when a queue is empty
 * the gateway behaves like a healthy server that accepts everything. Every call first goes through
 * what TLS does: a server whose key the pin does not name is refused before anything is sent.
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

    override suspend fun health(serverUrl: String, pin: ServerPin, challenge: String?): Outcome<ServerInfo> {
        healthCalls += serverUrl to challenge
        handshake(serverUrl, pin)?.let { return Outcome.Failure(it) }
        healthResponses.removeFirstOrNull()?.let { return it }
        val now = clock.now()
        val identity = challenge?.let {
            val key = keyAt(serverUrl)
            ServerIdentity(key, Base64.getEncoder().encodeToString(fakeSignature(key, serverIdentityInput(it, now))))
        }
        return Outcome.Success(ServerInfo("Fake", "0.1.0", ProtocolVersion.CURRENT, now, identity))
    }

    override suspend fun register(serverUrl: String, pin: ServerPin, registration: DeviceRegistration): Outcome<RegistrationResult> {
        handshake(serverUrl, pin)?.let { return Outcome.Failure(it) }
        registrations += registration
        return registerResponses.removeFirstOrNull()
            ?: Outcome.Success(RegistrationResult(registration.deviceId, registrations.size == 1, maxBatchSize, clock.now(), approval))
    }

    override suspend fun uploadLocations(
        serverUrl: String,
        pin: ServerPin,
        deviceId: DeviceId,
        locations: List<Location>,
    ): Outcome<LocationBatchResult> {
        handshake(serverUrl, pin)?.let { return Outcome.Failure(it) }
        uploadPins += pin
        uploads += locations
        val scripted = uploadResponses.removeFirstOrNull()
        return scripted?.invoke(locations) ?: acceptAll(locations)
    }

    fun acceptAll(locations: List<Location>): Outcome<LocationBatchResult> =
        Outcome.Success(LocationBatchResult(locations.map { it.id }, emptyList(), emptyList(), clock.now()))

    private fun keyAt(serverUrl: String): String = keysByUrl[serverUrl] ?: serverKey

    private fun handshake(serverUrl: String, pin: ServerPin): DomainError? {
        pins += pin
        return when {
            serverUrl in unreachable -> DomainError.Network.Unreachable
            !pin.matches(keyAt(serverUrl)) -> DomainError.ServerIdentityMismatch
            else -> null
        }
    }
}
