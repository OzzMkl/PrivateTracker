package org.privatetracker.server.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.DeserializationStrategy
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.dto.DevicesResponse
import org.privatetracker.core.protocol.v1.dto.HealthResponse
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationBatchResponse
import org.privatetracker.core.protocol.v1.dto.LocationDto
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val REGISTER_BODY =
    """{"device_id":"6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21","name":"Pixel de Ana","platform":"ANDROID","app_version":"0.1.0","protocol_version":1}"""

class PrivateTrackerApiTest {
    private val clock = FakeClock()
    private val store = InMemoryServerStore()
    private val config = InMemoryServerConfigRepository()
    private var local = true
    private var shuttingDown = false

    private fun dependencies(requestsPerMinute: Int): ServerDependencies {
        val sessions = SessionTracker(store, SequentialIdGenerator())
        return ServerDependencies(
            serverVersion = "0.1.0",
            clock = clock,
            serverConfig = config,
            registerOrUpdateDevice = RegisterOrUpdateDevice(store, config, sessions, ImmediateTransactionRunner, clock),
            ingestLocationBatch = IngestLocationBatch(
                store, store, store, config, sessions, LocationValidator(clock), ImmediateTransactionRunner, clock,
            ),
            getDeviceOverviews = GetDeviceOverviews(store, config, clock),
            getDeviceDetail = GetDeviceDetail(store, store, config, clock),
            requestsPerMinute = requestsPerMinute,
            isLocalRequest = { local },
            isShuttingDown = { shuttingDown },
        )
    }

    private fun api(requestsPerMinute: Int = 60, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { privateTrackerApi(dependencies(requestsPerMinute)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.register(): HttpResponse = postJson(ApiV1.REGISTER, REGISTER_BODY)

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
    fun `registration answers 201 the first time and 200 afterwards`() = api {
        val first = register()
        val second = register()

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(true, first.decode(RegisterDeviceResponse.serializer()).created)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(100, second.decode(RegisterDeviceResponse.serializer()).maxBatchSize)
    }

    @Test
    fun `malformed requests get problem responses with stable codes`() = api {
        val wrongType = client.post(ApiV1.REGISTER) {
            contentType(ContentType.Text.Plain)
            setBody(REGISTER_BODY)
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", wrongType.problem().code)

        val brokenJson = postJson(ApiV1.REGISTER, """{"device_id": """)
        assertEquals(HttpStatusCode.BadRequest, brokenJson.status)
        assertEquals("MALFORMED_JSON", brokenJson.problem().code)

        val badId = postJson(ApiV1.REGISTER, REGISTER_BODY.replace("6f1c2a8e", "zzzz"))
        assertEquals(HttpStatusCode.UnprocessableEntity, badId.status)
        assertEquals("device_id: invalid_format", badId.problem().detail)
    }

    @Test
    fun `locations from an unregistered device get 404 so the tracker registers`() = api {
        val response = postJson(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation().toDto()))

        assertEquals(HttpStatusCode.NotFound, response.status)
        val problem = response.problem()
        assertEquals("DEVICE_NOT_REGISTERED", problem.code)
        assertEquals("urn:privatetracker:problem:device-not-registered", problem.type)
    }

    @Test
    fun `a batch reports accepted, rejected and, when resent, duplicate locations`() = api {
        register()
        val body = batchBody(
            aLocation(1).toDto(),
            aLocation(2).toDto().copy(id = "not-a-uuid"),
            aLocation(3, latitude = 91.0).toDto(),
        )

        val first = postJson(ApiV1.locationsPath(DEVICE_A.value), body)
        assertEquals(HttpStatusCode.OK, first.status)
        val result = first.decode(LocationBatchResponse.serializer())
        assertEquals(listOf(locationId(1).value), result.accepted)
        assertEquals(listOf("not-a-uuid" to "INVALID_FIELD", locationId(3).value to "INVALID_COORDINATES"), result.rejected.map { it.id to it.code })

        val resent = postJson(ApiV1.locationsPath(DEVICE_A.value), body).decode(LocationBatchResponse.serializer())
        assertEquals(emptyList(), resent.accepted)
        assertEquals(listOf(locationId(1).value), resent.duplicates)
        assertEquals(1, store.storedLocations.size)
    }

    @Test
    fun `empty, oversized and huge batches are refused`() = api {
        register()
        config.update { it.copy(maxBatchSize = 2) }
        val path = ApiV1.locationsPath(DEVICE_A.value)

        assertEquals("EMPTY_BATCH", postJson(path, """{"locations":[]}""").problem().code)

        val tooMany = postJson(path, batchBody(aLocation(1).toDto(), aLocation(2).toDto(), aLocation(3).toDto()))
        assertEquals(HttpStatusCode.PayloadTooLarge, tooMany.status)
        assertEquals(2, tooMany.problem().maxBatchSize)

        val huge = postJson(path, """{"locations":[],"padding":"${"x".repeat(ApiV1.MAX_BODY_BYTES)}"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
    }

    @Test
    fun `read endpoints answer only local callers unless exposed`() = api {
        register()
        postJson(ApiV1.locationsPath(DEVICE_A.value), batchBody(aLocation(1).toDto()))

        val devices = client.get(ApiV1.DEVICES).decode(DevicesResponse.serializer()).devices.single()
        assertEquals("ONLINE", devices.status)
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
}
