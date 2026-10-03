package org.privatetracker.server.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.DeserializationStrategy
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.pairingProof
import org.privatetracker.core.domain.model.serverIdentityInput
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.core.protocol.v1.dto.DevicesResponse
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.LocationDto
import org.privatetracker.core.protocol.v1.dto.PairingClaimDto
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateTrackerApiTest {
    private val clock = FakeClock()
    private val store = InMemoryServerStore()
    private val config = InMemoryServerConfigRepository()
    private val keys = InMemoryDeviceKeys()
    private val serverKeys = InMemoryServerKeys()
    private val tickets = InMemoryPairingTicketStore()
    private var local = true
    private var shuttingDown = false
    private var requests = 0
    private var nonces = 0

    private fun dependencies(requestsPerMinute: Int): ServerDependencies {
        val sessions = SessionTracker(store, SequentialIdGenerator())
        val verifySignature = VerifyRequestSignature(EcdsaP256, InMemoryNonceRegistry(clock::now), clock)
        return ServerDependencies(
            serverVersion = "0.1.0",
            clock = clock,
            serverConfig = config,
            serverKeys = serverKeys,
            registerOrUpdateDevice = RegisterOrUpdateDevice(
                store, config, sessions, EcdsaP256, verifySignature, tickets, ImmediateTransactionRunner, clock,
            ),
            authenticateDevice = AuthenticateDevice(store, verifySignature),
            ingestLocationBatch = IngestLocationBatch(
                store, store, store, config, sessions, LocationValidator(clock), ImmediateTransactionRunner, clock,
            ),
            getDeviceOverviews = GetDeviceOverviews(store, config, clock),
            getDeviceDetail = GetDeviceDetail(store, store, config, clock),
            requestsPerMinute = requestsPerMinute,
            isLocalRequest = { local },
            isShuttingDown = { shuttingDown },
            onRequest = { requests++ },
        )
    }

    private fun api(requestsPerMinute: Int = 60, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { privateTrackerApi(dependencies(requestsPerMinute)) }
        block()
    }

    /**
     * A POST from [device], signed as a tracker signs it. The other parameters break the signature in
     * one way each: another device's key, another time, a reused nonce, another body, or none at all.
     */
    private suspend fun ApplicationTestBuilder.signedPost(
        path: String,
        body: String,
        device: DeviceId = DEVICE_A,
        keyOf: DeviceId = device,
        signedAt: Instant = clock.now(),
        nonce: String = "nonce-${++nonces}",
        signedBody: String = body,
        authorization: String? = null,
        unsigned: Boolean = false,
    ): HttpResponse {
        val input = RequestSignature.signingInput("POST", path, device.value, signedAt.epochSecond, nonce, signedBody.encodeToByteArray())
        val signature = RequestSignature.header(device.value, signedAt.epochSecond, nonce, keys.sign(keyOf, input))
        return client.post(path) {
            contentType(ContentType.Application.Json)
            if (!unsigned) header(HttpHeaders.Authorization, authorization ?: signature)
            setBody(body)
        }
    }

    private suspend fun registerBody(device: DeviceId = DEVICE_A, keyOf: DeviceId = device): String =
        ProtocolJson.encodeToString(
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(device.value, "Pixel de Ana", "ANDROID", "0.1.0", 1, keys.publicKey(keyOf)),
        )

    private suspend fun ApplicationTestBuilder.register(device: DeviceId = DEVICE_A): HttpResponse =
        signedPost(ApiV1.REGISTER, registerBody(device), device)

    /** Registered and approved, as after the owner tapped Approve. */
    private suspend fun ApplicationTestBuilder.enroll(device: DeviceId = DEVICE_A) {
        assertEquals(HttpStatusCode.Created, register(device).status)
        store.update(store.get(device)!!.copy(approval = DeviceApproval.APPROVED))
    }

    private fun batchBody(vararg locations: LocationDto): String =
        ProtocolJson.encodeToString(LocationBatchRequest.serializer(), LocationBatchRequest(locations.toList()))

    private suspend fun <T> HttpResponse.decode(deserializer: DeserializationStrategy<T>): T =
        ProtocolJson.decodeFromString(deserializer, bodyAsText())

    private suspend fun HttpResponse.problem(): ProblemDetails {
        assertEquals(ApiV1.PROBLEM_CONTENT_TYPE, headers[HttpHeaders.ContentType]?.substringBefore(';'))
        return decode(ProblemDetails.serializer())
    }

    @Test
    fun `health reports the server name and protocol version`() = api {
        val response = client.get(ApiV1.HEALTH)

        assertEquals(HttpStatusCode.OK, response.status)
        val health = response.decode(HealthResponse.serializer())
        assertEquals("PrivateTracker Server", health.serverName)
        assertEquals(1, health.protocolVersion)
        assertEquals("2026-10-02T18:00:00Z", health.serverTime)
    }

    @Test
    fun `health signs a challenge with the server key, so a tracker can tell its server apart`() = api {
        val challenge = "Y2hhbGxlbmdlLTEyMzQ1Ng"

        val health = client.get(ApiV1.HEALTH) { parameter(ApiV1.CHALLENGE_PARAM, challenge) }.decode(HealthResponse.serializer())

        val info = health.toDomain().successValue()
        val identity = assertNotNull(info.identity)
        assertEquals(serverKeys.publicKey(), identity.publicKey)
        val signature = Base64.getDecoder().decode(identity.signature)
        assertTrue(EcdsaP256.verify(identity.publicKey, serverIdentityInput(challenge, info.serverTime), signature))
        // No challenge, or a malformed one: nothing signed.
        assertNull(client.get(ApiV1.HEALTH).decode(HealthResponse.serializer()).signature)
        assertNull(client.get(ApiV1.HEALTH) { parameter(ApiV1.CHALLENGE_PARAM, "short") }.decode(HealthResponse.serializer()).signature)
    }

    @Test
    fun `a registration with a valid pairing proof is approved at once, and the ticket serves only once`() = api {
        val invite = CreatePairingInvite(tickets, serverKeys, config, clock)(listOf("http://localhost")).successValue()
        suspend fun pairedBody(device: DeviceId): String = ProtocolJson.encodeToString(
            RegisterDeviceRequest.serializer(),
            RegisterDeviceRequest(
                device.value, "Pixel de Ana", "ANDROID", "0.3.0", 1, keys.publicKey(device),
                PairingClaimDto(invite.ticketId, pairingProof(invite.secret, device, keys.publicKey(device))),
            ),
        )

        val paired = signedPost(ApiV1.REGISTER, pairedBody(DEVICE_A))
        assertEquals("APPROVED", paired.decode(RegisterDeviceResponse.serializer()).approval)
        assertEquals(HttpStatusCode.OK, signedPost(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation(1).toDto())).status)

        val second = signedPost(ApiV1.REGISTER, pairedBody(DEVICE_B), device = DEVICE_B)
        assertEquals(HttpStatusCode.Forbidden, second.status)
        assertEquals("PAIRING_INVALID", second.problem().code)
        assertNull(store.get(DEVICE_B))
    }

    @Test
    fun `registration answers 201 pending the first time and 200 afterwards`() = api {
        val first = register()
        val second = register()

        assertEquals(HttpStatusCode.Created, first.status)
        val created = first.decode(RegisterDeviceResponse.serializer())
        assertEquals(true, created.created)
        assertEquals("PENDING", created.approval)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(100, second.decode(RegisterDeviceResponse.serializer()).maxBatchSize)
        assertEquals(keys.publicKey(DEVICE_A), store.get(DEVICE_A)?.publicKey)
    }

    @Test
    fun `a device not approved or with an invalid signature is refused on every device route`() = api {
        enroll(DEVICE_A)
        val routes = listOf(
            ApiV1.REGISTER to registerBody(DEVICE_A),
            ApiV1.locationsPath(DEVICE_A.value) to batchBody(aLocation(1).toDto()),
        )
        for ((index, route) in routes.withIndex()) {
            val (path, body) = route
            suspend fun refused(expected: String, response: HttpResponse) {
                assertEquals(HttpStatusCode.Unauthorized, response.status, "$path: $expected")
                assertEquals(expected, response.problem().code, path)
                assertEquals(RequestSignature.SCHEME, response.headers[HttpHeaders.WWWAuthenticate])
            }
            refused("SIGNATURE_MISSING", signedPost(path, body, unsigned = true))
            refused("SIGNATURE_MALFORMED", signedPost(path, body, authorization = "Bearer token"))
            refused("SIGNATURE_INVALID", signedPost(path, body, keyOf = DEVICE_B))
            refused("SIGNATURE_INVALID", signedPost(path, body, signedBody = "$body "))
            refused("SIGNATURE_EXPIRED", signedPost(path, body, signedAt = clock.now().minus(Duration.ofMinutes(6))))
            refused("SIGNATURE_EXPIRED", signedPost(path, body, signedAt = clock.now().plus(Duration.ofMinutes(6))))
            signedPost(path, body, nonce = "once-$index").also { assertTrue(it.status.value < 300, "$path: ${it.status}") }
            refused("SIGNATURE_REPLAYED", signedPost(path, body, nonce = "once-$index"))
        }
        // A device signs only for itself.
        val foreign = signedPost(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation(2).toDto()), device = DEVICE_B)
        assertEquals("SIGNATURE_INVALID", foreign.problem().code)

        // A known id with another key: the original device keeps its place.
        val takeover = signedPost(ApiV1.REGISTER, registerBody(DEVICE_A, keyOf = DEVICE_B), keyOf = DEVICE_B)
        assertEquals(HttpStatusCode.Conflict, takeover.status)
        assertEquals("KEY_MISMATCH", takeover.problem().code)

        // Only the one accepted batch above got through.
        assertEquals(listOf(locationId(1)), store.storedLocations.map { it.location.id })
    }

    @Test
    fun `a signature counts only for the request it was made for`() = api {
        enroll(DEVICE_A)
        // Captured from a registration, then sent to the locations route with the same nonce and time.
        val signedAt = clock.now()
        val input = RequestSignature.signingInput("POST", ApiV1.REGISTER, DEVICE_A.value, signedAt.epochSecond, "captured", registerBody().encodeToByteArray())
        val captured = RequestSignature.header(DEVICE_A.value, signedAt.epochSecond, "captured", keys.sign(DEVICE_A, input))
        val moved = signedPost(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation(1).toDto()), authorization = captured)
        assertEquals("SIGNATURE_INVALID", moved.problem().code)

        // The body must name the device that signs.
        val otherBody = signedPost(ApiV1.REGISTER, registerBody(DEVICE_B), device = DEVICE_A)
        assertEquals("SIGNATURE_INVALID", otherBody.problem().code)
        assertTrue(store.storedLocations.isEmpty())
    }

    @Test
    fun `an id written in capitals names the same device, under its own signature`() = api {
        enroll(DEVICE_A)
        val upper = DeviceId.of(DEVICE_A.value.uppercase())
        val path = ApiV1.locationsPath(DEVICE_A.value)
        val body = batchBody(aLocation(1).toDto())
        val signedAt = clock.now()
        val input = RequestSignature.signingInput("POST", path, DEVICE_A.value.uppercase(), signedAt.epochSecond, "caps", body.encodeToByteArray())
        val header = RequestSignature.header(DEVICE_A.value.uppercase(), signedAt.epochSecond, "caps", keys.sign(upper, input))

        assertEquals(HttpStatusCode.OK, signedPost(path, body, authorization = header).status)
        assertEquals(listOf(DEVICE_A), store.storedLocations.map { it.location.deviceId })
    }

    @Test
    fun `a device that 0_1 registered is sent to register again, and then waits for approval`() = api {
        store.insert(
            Device(DEVICE_A, "Pixel de Ana", Platform.ANDROID, "0.1.0", 1, createdAt = clock.now(), lastSeenAt = null, approval = DeviceApproval.APPROVED),
        )
        val path = ApiV1.locationsPath(DEVICE_A.value)

        assertEquals("DEVICE_NOT_REGISTERED", signedPost(path, batchBody(aLocation(1).toDto())).problem().code)
        val again = register(DEVICE_A)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals("PENDING", again.decode(RegisterDeviceResponse.serializer()).approval)
        assertEquals("DEVICE_PENDING_APPROVAL", signedPost(path, batchBody(aLocation(1).toDto())).problem().code)
    }

    @Test
    fun `locations from pending and rejected devices are refused and not stored`() = api {
        register(DEVICE_A)
        val path = ApiV1.locationsPath(DEVICE_A.value)

        val pending = signedPost(path, batchBody(aLocation(1).toDto()))
        assertEquals(HttpStatusCode.Forbidden, pending.status)
        assertEquals("DEVICE_PENDING_APPROVAL", pending.problem().code)

        store.update(store.get(DEVICE_A)!!.copy(approval = DeviceApproval.REJECTED))
        val rejected = signedPost(path, batchBody(aLocation(1).toDto()))
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertEquals("DEVICE_REJECTED", rejected.problem().code)
        assertEquals("DEVICE_REJECTED", register(DEVICE_A).problem().code)

        assertTrue(store.storedLocations.isEmpty())
    }

    @Test
    fun `malformed requests get problem responses with stable codes`() = api {
        val wrongType = client.post(ApiV1.REGISTER) {
            contentType(ContentType.Text.Plain)
            setBody(registerBody())
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", wrongType.problem().code)

        val brokenJson = signedPost(ApiV1.REGISTER, """{"device_id": """)
        assertEquals(HttpStatusCode.BadRequest, brokenJson.status)
        assertEquals("MALFORMED_JSON", brokenJson.problem().code)

        val badId = signedPost(ApiV1.REGISTER, registerBody().replace(DEVICE_A.value, "zzzz"))
        assertEquals(HttpStatusCode.UnprocessableEntity, badId.status)
        assertEquals("device_id: invalid_format", badId.problem().detail)

        val noKey = signedPost(ApiV1.REGISTER, registerBody().replace(Regex(""","public_key":"[^"]*""""), ""))
        assertEquals("public_key: required", noKey.problem().detail)
    }

    @Test
    fun `locations from an unregistered device get 404 so the tracker registers`() = api {
        val response = signedPost(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation().toDto()))

        assertEquals(HttpStatusCode.NotFound, response.status)
        val problem = response.problem()
        assertEquals("DEVICE_NOT_REGISTERED", problem.code)
        assertEquals("urn:privatetracker:problem:device-not-registered", problem.type)
    }

    @Test
    fun `a batch reports accepted, rejected and, when resent, duplicate locations`() = api {
        enroll()
        val body = batchBody(
            aLocation(1).toDto(),
            aLocation(2).toDto().copy(id = "not-a-uuid"),
            aLocation(3, latitude = 91.0).toDto(),
        )

        val first = signedPost(ApiV1.locationsPath(DEVICE_A.value), body, nonce = "batch-1")
        assertEquals(HttpStatusCode.OK, first.status)
        // The answer is signed with the server key over this request's nonce and the exact body.
        val ack = Base64.getDecoder().decode(assertNotNull(first.headers[RequestSignature.ACK_HEADER]))
        assertTrue(EcdsaP256.verify(serverKeys.publicKey(), RequestSignature.ackInput("batch-1", first.bodyAsBytes()), ack))
        val result = first.decode(LocationBatchResponse.serializer())
        assertEquals(listOf(locationId(1).value), result.accepted)
        assertEquals(listOf("not-a-uuid" to "INVALID_FIELD", locationId(3).value to "INVALID_COORDINATES"), result.rejected.map { it.id to it.code })

        val resent = signedPost(ApiV1.locationsPath(DEVICE_A.value), body).decode(LocationBatchResponse.serializer())
        assertEquals(emptyList(), resent.accepted)
        assertEquals(listOf(locationId(1).value), resent.duplicates)
        assertEquals(1, store.storedLocations.size)
    }

    @Test
    fun `empty, oversized and huge batches are refused`() = api {
        enroll()
        config.update { it.copy(maxBatchSize = 2) }
        val path = ApiV1.locationsPath(DEVICE_A.value)

        assertEquals("EMPTY_BATCH", signedPost(path, """{"locations":[]}""").problem().code)

        val tooMany = signedPost(path, batchBody(aLocation(1).toDto(), aLocation(2).toDto(), aLocation(3).toDto()))
        assertEquals(HttpStatusCode.PayloadTooLarge, tooMany.status)
        assertEquals(2, tooMany.problem().maxBatchSize)

        val huge = signedPost(path, """{"locations":[],"padding":"${"x".repeat(ApiV1.MAX_BODY_BYTES)}"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
    }

    @Test
    fun `read endpoints answer only local callers unless exposed`() = api {
        enroll()
        signedPost(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation(1).toDto()))

        val devices = client.get(ApiV1.DEVICES).decode(DevicesResponse.serializer()).devices.single()
        assertEquals("ONLINE", devices.status)
        assertEquals("APPROVED", devices.approval)
        assertEquals(locationId(1).value, devices.lastLocation?.id)

        local = false
        val remote = client.get(ApiV1.DEVICES)
        assertEquals(HttpStatusCode.Forbidden, remote.status)
        assertEquals("LOCAL_ONLY", remote.problem().code)

        config.update { it.copy(exposeReadApi = true) }
        assertEquals(HttpStatusCode.OK, client.get(ApiV1.devicePath(DEVICE_A.value)).status)
    }

    @Test
    fun `unknown devices and routes get 404 problems`() = api {
        assertEquals("DEVICE_NOT_FOUND", client.get(ApiV1.devicePath(DEVICE_A.value)).problem().code)
        assertEquals("NOT_FOUND", client.get("/api/v2/health").problem().code)
    }

    @Test
    fun `too many requests get 429 with Retry-After`() = api(requestsPerMinute = 2) {
        repeat(2) { register() }

        val limited = register()

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        assertEquals("RATE_LIMITED", limited.problem().code)
    }

    @Test
    fun `a stopping server answers 503 so trackers keep their outbox`() = api {
        shuttingDown = true

        val response = register()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("30", response.headers[HttpHeaders.RetryAfter])
        assertTrue(store.getAllWithLastLocation().isEmpty())
    }

    @Test
    fun `every request is counted, refused ones included`() = api {
        client.get(ApiV1.HEALTH)
        register()
        client.get("/nothing-here")

        assertEquals(3, requests)
    }
}
