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
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.ServerPin
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
import org.privatetracker.core.protocol.v1.SealedBodies
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLHandshakeException

/**
 * Client for protocol v1. Every expected failure comes back as a [DomainError], never as an exception.
 * Every request goes over TLS to the server its pin names, through [clients]; device routes also travel
 * sealed for the server's encryption key, see [SealedBodies], and signed with the device's key over
 * the sealed bytes, see [RequestSignature].
 */
class KtorServerGateway(
    private val clients: PinnedClients,
    private val keys: DeviceKeys,
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) : ServerGateway {

    override suspend fun health(serverUrl: String, pin: ServerPin, challenge: String?): Outcome<ServerInfo> =
        exchange(HealthResponse.serializer()) {
            clients.clientFor(pin).get(url(serverUrl, ApiV1.HEALTH)) { challenge?.let { parameter(ApiV1.CHALLENGE_PARAM, it) } }
        }.flatMap { it.toDomain() }

    override suspend fun register(
        serverUrl: String,
        pin: ServerPin,
        registration: DeviceRegistration,
        encryptionKey: EncryptionKey,
    ): Outcome<RegistrationResult> {
        val body = ProtocolJson.encodeToString(RegisterDeviceRequest.serializer(), registration.toDto())
        return postSealed(serverUrl, pin, ApiV1.REGISTER, registration.deviceId, body, encryptionKey, RegisterDeviceResponse.serializer())
            .flatMap { it.toDomain() }
    }

    override suspend fun uploadLocations(
        serverUrl: String,
        pin: ServerPin,
        deviceId: DeviceId,
        locations: List<Location>,
        encryptionKey: EncryptionKey,
    ): Outcome<LocationBatchResult> {
        val body = ProtocolJson.encodeToString(LocationBatchRequest.serializer(), LocationBatchRequest(locations.map { it.toDto() }))
        // TLS already ties the answer to the pinned key; the signed acknowledgment holds even if TLS does not.
        val ackKey = (pin as? ServerPin.Key)?.publicKey
        val path = ApiV1.locationsPath(deviceId.value)
        return postSealed(serverUrl, pin, path, deviceId, body, encryptionKey, LocationBatchResponse.serializer(), ackKey)
            .flatMap { it.toDomain() }
    }

    /**
     * Seals [body] for [encryptionKey] and signs exactly the sealed bytes it sends, so the server hashes
     * the same body. Sealing and signing happen before the request, so a key store failure is reported
     * as such instead of as a network error. Only an answer that opens with this request's key counts.
     */
    private suspend fun <T> postSealed(
        serverUrl: String,
        pin: ServerPin,
        path: String,
        deviceId: DeviceId,
        body: String,
        encryptionKey: EncryptionKey,
        deserializer: DeserializationStrategy<T>,
        serverKey: String? = null,
    ): Outcome<T> {
        // Signed by the server's own key, yet not a P-256 key: nothing this tracker can seal for.
        val sealed = SealedBodies.seal(encryptionKey, "POST", path, body.encodeToByteArray())
            ?: return DomainError.EncryptionUnavailable.asFailure()
        val bytes = sealed.body
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
        return exchange(deserializer, acknowledgedBy, open = { SealedBodies.openResponse(sealed, it) }) {
            clients.clientFor(pin).post(url(serverUrl, path)) {
                header(HttpHeaders.Authorization, RequestSignature.header(deviceId.value, created, nonce, signature))
                setBody(ByteArrayContent(bytes, ContentType.Application.Json))
            }
        }
    }

    /**
     * [acknowledgedBy] checks a successful answer before it is believed; a failed check is a foreign
     * server. [open] reads a sealed answer; one that does not open is no answer to this request.
     */
    private suspend fun <T> exchange(
        deserializer: DeserializationStrategy<T>,
        acknowledgedBy: ((HttpResponse, ByteArray) -> Boolean)? = null,
        open: ((ByteArray) -> ByteArray?)? = null,
        request: suspend () -> HttpResponse,
    ): Outcome<T> {
        val (response, bytes) = try {
            val response = request()
            response to response.bodyAsBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.toDomainError().asFailure()
        }
        val body = bytes.decodeToString()

        if (!response.status.isSuccess()) {
            val problem = runCatching { ProtocolJson.decodeFromString(ProblemDetails.serializer(), body) }.getOrNull()
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            return problem.toDomainError(response.status.value, retryAfter).asFailure()
        }
        if (acknowledgedBy != null && !acknowledgedBy(response, bytes)) return DomainError.ServerIdentityMismatch.asFailure()
        val plain = if (open == null) body else open(bytes)?.decodeToString()
            ?: return DomainError.Network.InvalidResponse("sealed answer does not open").asFailure()
        return try {
            Outcome.Success(ProtocolJson.decodeFromString(deserializer, plain))
        } catch (e: SerializationException) {
            DomainError.Network.InvalidResponse(e.message?.lineSequence()?.firstOrNull() ?: "unreadable body").asFailure()
        } catch (e: IllegalArgumentException) {
            DomainError.Network.InvalidResponse(e.message?.lineSequence()?.firstOrNull() ?: "unreadable body").asFailure()
        }
    }

    private fun url(serverUrl: String, path: String) = serverUrl.trimEnd('/') + path
}

/**
 * A refused server key is the server's identity failing, not the network. Any other failed handshake,
 * such as with a server that still speaks plain HTTP, means something that is not a PrivateTracker
 * server answered; a connection dropped later is just the network.
 */
internal fun Throwable.toDomainError(): DomainError = when {
    causes().any { it is ServerKeyMismatchException } -> DomainError.ServerIdentityMismatch
    causes().any { it is SSLHandshakeException } -> DomainError.Network.InvalidResponse("TLS handshake failed")
    else -> when (this) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is java.net.SocketTimeoutException,
        -> DomainError.Network.Timeout
        else -> DomainError.Network.Unreachable
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)

private const val MAX_CAUSES = 16

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
