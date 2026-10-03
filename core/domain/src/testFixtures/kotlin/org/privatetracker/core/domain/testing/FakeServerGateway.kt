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
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.port.ServerGateway
import java.util.Base64

/**
 * Scriptable server. Queued responses are used first, in order; when a queue is empty
 * the gateway behaves like a healthy server that accepts everything.
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

    /** When set, health answers a challenge with this key, signed as [FakeSignatureVerifier] expects. */
    var serverKey: String? = null

    /** Another server at some addresses, with its own key. */
    val keysByUrl = mutableMapOf<String, String>()

    /** Addresses where nothing answers. */
    val unreachable = mutableSetOf<String>()

    override suspend fun health(serverUrl: String, challenge: String?): Outcome<ServerInfo> {
        healthCalls += serverUrl to challenge
        if (serverUrl in unreachable) return Outcome.Failure(DomainError.Network.Unreachable)
        healthResponses.removeFirstOrNull()?.let { return it }
        val now = clock.now()
        val identity = (keysByUrl[serverUrl] ?: serverKey)?.takeIf { challenge != null }?.let { key ->
            ServerIdentity(key, Base64.getEncoder().encodeToString(fakeSignature(key, serverIdentityInput(challenge!!, now))))
        }
        return Outcome.Success(ServerInfo("Fake", "0.1.0", ProtocolVersion.CURRENT, now, identity))
    }

    override suspend fun register(serverUrl: String, registration: DeviceRegistration): Outcome<RegistrationResult> {
        if (serverUrl in unreachable) return Outcome.Failure(DomainError.Network.Unreachable)
        registrations += registration
        return registerResponses.removeFirstOrNull()
            ?: Outcome.Success(RegistrationResult(registration.deviceId, registrations.size == 1, maxBatchSize, clock.now(), approval))
    }

    /** The key each upload asked the server to sign with. */
    val uploadKeys = mutableListOf<String?>()

    override suspend fun uploadLocations(
        serverUrl: String,
        deviceId: DeviceId,
        locations: List<Location>,
        serverKey: String?,
    ): Outcome<LocationBatchResult> {
        uploadKeys += serverKey
        uploads += locations
        val scripted = uploadResponses.removeFirstOrNull()
        return scripted?.invoke(locations) ?: acceptAll(locations)
    }

    fun acceptAll(locations: List<Location>): Outcome<LocationBatchResult> =
        Outcome.Success(LocationBatchResult(locations.map { it.id }, emptyList(), emptyList(), clock.now()))
}
