package org.privatetracker.core.domain.port

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerInfo

/** Runs [run]'s block atomically: all repository writes inside it commit together or not at all. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/**
 * The tracker's view of a remote server. Network and HTTP failures are expected here,
 * so they come back as [Outcome.Failure] instead of exceptions.
 */
interface ServerGateway {
    suspend fun health(serverUrl: String): Outcome<ServerInfo>
    suspend fun register(serverUrl: String, registration: DeviceRegistration): Outcome<RegistrationResult>
    suspend fun uploadLocations(
        serverUrl: String,
        deviceId: DeviceId,
        locations: List<Location>,
    ): Outcome<LocationBatchResult>
}

data class LocationRequestSpec(
    val intervalMillis: Long,
    val minDistanceM: Float,
    val priority: LocationPriority,
)

/** Stream of fixes from the platform location service while collected. */
interface LocationSource {
    fun locations(request: LocationRequestSpec): Flow<LocationFix>
}

fun interface BatteryLevelProvider {
    /** Battery level from 0 to 100, or null when unknown. */
    fun currentLevelPct(): Int?
}
