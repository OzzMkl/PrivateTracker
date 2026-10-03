package org.privatetracker.core.domain.testing

import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.port.ServerGateway

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

    override suspend fun health(serverUrl: String): Outcome<ServerInfo> =
        healthResponses.removeFirstOrNull()
            ?: Outcome.Success(ServerInfo("Fake", "0.1.0", ProtocolVersion.CURRENT, clock.now()))

    override suspend fun register(serverUrl: String, registration: DeviceRegistration): Outcome<RegistrationResult> {
        registrations += registration
        return registerResponses.removeFirstOrNull()
            ?: Outcome.Success(RegistrationResult(registration.deviceId, registrations.size == 1, maxBatchSize, clock.now()))
    }

    override suspend fun uploadLocations(
        serverUrl: String,
        deviceId: DeviceId,
        locations: List<Location>,
    ): Outcome<LocationBatchResult> {
        uploads += locations
        val scripted = uploadResponses.removeFirstOrNull()
        return scripted?.invoke(locations) ?: acceptAll(locations)
    }

    fun acceptAll(locations: List<Location>): Outcome<LocationBatchResult> =
        Outcome.Success(LocationBatchResult(locations.map { it.id }, emptyList(), emptyList(), clock.now()))
}
