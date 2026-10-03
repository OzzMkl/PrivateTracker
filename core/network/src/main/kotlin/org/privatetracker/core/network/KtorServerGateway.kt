package org.privatetracker.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.flatMap
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDomainError
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import java.security.SecureRandom
import java.util.Base64

/**
 * Client for protocol v1. Every expected failure comes back as a [DomainError], never as an exception.
 * Device routes are signed with the device's key; see [RequestSignature].
 */
class KtorServerGateway(
    private val client: HttpClient,
    private val keys: DeviceKeys,
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) : ServerGateway {

    override suspend fun health(serverUrl: String, challenge: String?): Outcome<ServerInfo> =
        exchange(HealthResponse.serializer()) {
            client.get(url(serverUrl, ApiV1.HEALTH)) { challenge?.let { parameter(ApiV1.CHALLENGE_PARAM, it) } }
        }.flatMap { it.toDomain() }

    override suspend fun register(serverUrl: String, registration: DeviceRegistration): Outcome<RegistrationResult> {
        val body = ProtocolJson.encodeToString(RegisterDeviceRequest.serializer(), registration.toDto())
        return postSigned(serverUrl, ApiV1.REGISTER, registration.deviceId, body, RegisterDeviceResponse.serializer())
            .flatMap { it.toDomain() }
    }

    override suspend fun uploadLocations(
        serverUrl: String,
        deviceId: DeviceId,
        locations: List<Location>,
        serverKey: String?,
    ): Outcome<LocationBatchResult> {
        val body = ProtocolJson.encodeToString(LocationBatchRequest.serializer(), LocationBatchRequest(locations.map { it.toDto() }))
        return postSigned(serverUrl, ApiV1.locationsPath(deviceId.value), deviceId, body, LocationBatchResponse.serializer(), serverKey)
            .flatMap { it.toDomain() }
    }

    /**
     * Signs exactly the bytes it sends, so the server hashes the same body. Signing happens before the
     * request, so a key store failure is reported as such instead of as a network error.
     */
    private suspend fun <T> postSigned(
        serverUrl: String,
        path: String,
        deviceId: DeviceId,
        body: String,
        deserializer: DeserializationStrategy<T>,
        serverKey: String? = null,
    ): Outcome<T> {
        val bytes = body.encodeToByteArray()
        val created = clock.now().epochSecond
        val nonce = RequestSignature.newNonce(random)
        val input = RequestSignature.signingInput("POST", path, deviceId.value, created, nonce, bytes)
        val signature = try {
            keys.sign(deviceId, input)
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        val acknowledgedBy: ((HttpResponse, ByteArray) -> Boolean)? = serverKey?.let { key ->
            { response, responseBody ->
                val ack = response.headers[RequestSignature.ACK_HEADER]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ack != null && EcdsaP256.verify(key, RequestSignature.ackInput(nonce, responseBody), ack)
            }
        }
        return exchange(deserializer, acknowledgedBy) {
            client.post(url(serverUrl, path)) {
                header(HttpHeaders.Authorization, RequestSignature.header(deviceId.value, created, nonce, signature))
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }
        }
    }

    /** [acknowledgedBy] checks a successful answer before it is believed; a failed check is a foreign server. */
    private suspend fun <T> exchange(
        deserializer: DeserializationStrategy<T>,
        acknowledgedBy: ((HttpResponse, ByteArray) -> Boolean)? = null,
        request: suspend () -> HttpResponse,
    ): Outcome<T> {
        val (response, bytes) = try {
            val response = request()
            response to response.bodyAsBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.toNetworkError().asFailure()
        }
        val body = bytes.decodeToString()

        if (!response.status.isSuccess()) {
            val problem = runCatching { ProtocolJson.decodeFromString(ProblemDetails.serializer(), body) }.getOrNull()
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            return problem.toDomainError(response.status.value, retryAfter).asFailure()
        }
        if (acknowledgedBy != null && !acknowledgedBy(response, bytes)) return DomainError.ServerIdentityMismatch.asFailure()
        return try {
            Outcome.Success(ProtocolJson.decodeFromString(deserializer, body))
        } catch (e: SerializationException) {
            DomainError.Network.InvalidResponse(e.message?.lineSequence()?.firstOrNull() ?: "unreadable body").asFailure()
        } catch (e: IllegalArgumentException) {
            DomainError.Network.InvalidResponse(e.message?.lineSequence()?.firstOrNull() ?: "unreadable body").asFailure()
        }
    }

    private fun url(serverUrl: String, path: String) = serverUrl.trimEnd('/') + path
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
    // A host that took over an address could otherwise send a health challenge on to the real server
    // and collect its signature. Nothing in the protocol redirects, so a redirect is just an error.
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 30_000
    }
    install(UserAgent) { agent = userAgent }
}
