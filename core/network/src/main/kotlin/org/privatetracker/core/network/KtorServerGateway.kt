package org.privatetracker.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.flatMap
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDomainError
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse

/** Client for protocol v1. Every expected failure comes back as a [DomainError], never as an exception. */
class KtorServerGateway(private val client: HttpClient) : ServerGateway {

    override suspend fun health(serverUrl: String): Outcome<ServerInfo> =
        exchange(HealthResponse.serializer()) { client.get(url(serverUrl, ApiV1.HEALTH)) }
            .flatMap { it.toDomain() }

    override suspend fun register(serverUrl: String, registration: DeviceRegistration): Outcome<RegistrationResult> =
        exchange(RegisterDeviceResponse.serializer()) {
            client.post(url(serverUrl, ApiV1.REGISTER)) { json(RegisterDeviceRequest.serializer(), registration.toDto()) }
        }.flatMap { it.toDomain() }

    override suspend fun uploadLocations(
        serverUrl: String,
        deviceId: DeviceId,
        locations: List<Location>,
    ): Outcome<LocationBatchResult> =
        exchange(LocationBatchResponse.serializer()) {
            client.post(url(serverUrl, ApiV1.locationsPath(deviceId.value))) {
                json(LocationBatchRequest.serializer(), LocationBatchRequest(locations.map { it.toDto() }))
            }
        }.flatMap { it.toDomain() }

    private suspend fun <T> exchange(
        deserializer: DeserializationStrategy<T>,
        request: suspend () -> HttpResponse,
    ): Outcome<T> {
        val (response, body) = try {
            val response = request()
            response to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.toNetworkError().asFailure()
        }

        if (!response.status.isSuccess()) {
            val problem = runCatching { ProtocolJson.decodeFromString(ProblemDetails.serializer(), body) }.getOrNull()
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            return problem.toDomainError(response.status.value, retryAfter).asFailure()
        }
        return try {
            Outcome.Success(ProtocolJson.decodeFromString(deserializer, body))
        } catch (e: SerializationException) {
            DomainError.Network.InvalidResponse(e.message?.lineSequence()?.firstOrNull() ?: "unreadable body").asFailure()
        } catch (e: IllegalArgumentException) {
            DomainError.Network.InvalidResponse(e.message?.lineSequence()?.firstOrNull() ?: "unreadable body").asFailure()
        }
    }

    private fun url(serverUrl: String, path: String) = serverUrl.trimEnd('/') + path

    private fun <T> HttpRequestBuilder.json(serializer: SerializationStrategy<T>, value: T) {
        setBody(TextContent(ProtocolJson.encodeToString(serializer, value), ContentType.Application.Json))
    }
}

internal fun Throwable.toNetworkError(): DomainError.Network = when (this) {
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is io.ktor.client.network.sockets.SocketTimeoutException,
    is java.net.SocketTimeoutException,
    -> DomainError.Network.Timeout
    else -> DomainError.Network.Unreachable
}

/** HTTP client configured for the protocol: no exceptions on error statuses, bounded timeouts, own User-Agent. */
fun createProtocolHttpClient(engine: HttpClientEngine, userAgent: String): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 30_000
    }
    install(UserAgent) { agent = userAgent }
}
