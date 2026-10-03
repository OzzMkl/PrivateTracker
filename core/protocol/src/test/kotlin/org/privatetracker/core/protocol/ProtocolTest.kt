package org.privatetracker.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.RejectedLocation
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.protocol.mapper.WireTime
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDomainError
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.dto.LocationBatchRequest
import org.privatetracker.core.protocol.v1.dto.LocationDto
import org.privatetracker.core.protocol.v1.dto.ProblemDetails
import org.privatetracker.core.protocol.v1.dto.RegisterDeviceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SerializationTest {
    @Test
    fun `locations use snake_case and omit absent optional fields`() {
        val json = ProtocolJson.encodeToString(
            LocationDto.serializer(),
            LocationDto(id = "x", latitude = 19.4326, longitude = -99.1332, accuracyM = 8.5f, recordedAt = "2026-10-02T18:29:41Z"),
        )

        val fields = Json.parseToJsonElement(json).jsonObject.keys
        assertEquals(setOf("id", "latitude", "longitude", "accuracy_m", "is_mock", "recorded_at"), fields)
    }

    @Test
    fun `the example batch from the design document decodes`() {
        val body = """
            {"locations":[{"id":"b3e1f7a2-5c4d-4e8f-a1b2-9c0d7e6f5a43","latitude":19.4326,"longitude":-99.1332,
            "accuracy_m":8.5,"altitude_m":2240.0,"speed_mps":1.2,"bearing_deg":87.0,"provider":"fused",
            "battery_pct":76,"is_mock":false,"recorded_at":"2026-10-02T18:29:41Z"}]}
        """.trimIndent()

        val batch = ProtocolJson.decodeFromString(LocationBatchRequest.serializer(), body)

        assertEquals(76, batch.locations.single().batteryPct)
    }

    @Test
    fun `fields added by a newer peer are ignored`() {
        val body = """{"device_id":"6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21","name":"Ana","platform":"ANDROID",
            "protocol_version":1,"public_key":"added in 0.2"}"""

        val request = ProtocolJson.decodeFromString(RegisterDeviceRequest.serializer(), body)

        assertEquals("Ana", request.name)
    }

    @Test
    fun `problem details carry type, status and code`() {
        val problem = ProblemDetails(
            type = ErrorCode.DEVICE_NOT_REGISTERED.problemType,
            title = "Device not registered",
            status = 404,
            code = ErrorCode.DEVICE_NOT_REGISTERED.name,
        )

        val json = Json.parseToJsonElement(ProtocolJson.encodeToString(ProblemDetails.serializer(), problem)) as JsonObject

        assertEquals("\"urn:privatetracker:problem:device-not-registered\"", json["type"].toString())
        assertFalse("max_batch_size" in json)
    }
}

class MappingTest {
    @Test
    fun `a location survives the round trip through the wire`() {
        val location = aLocation()

        assertEquals(location, location.toDto().toDomain(DEVICE_A).successValue())
    }

    @Test
    fun `timestamps are written in UTC and read with any offset`() {
        assertEquals("2026-10-02T18:00:00Z", WireTime.format(T0))
        assertEquals(T0, WireTime.parse("2026-10-02T12:00:00-06:00"))
        assertEquals(null, WireTime.parse("2026-10-02 18:00"))
    }

    @Test
    fun `a location with a malformed id or timestamp maps to a validation error naming both fields`() {
        val dto = aLocation().toDto().copy(id = "nope", recordedAt = "yesterday")

        val error = assertIs<DomainError.Validation>(dto.toDomain(DEVICE_A).failureError())

        assertEquals(
            listOf(FieldViolation("id", FieldViolation.INVALID_FORMAT), FieldViolation("recorded_at", FieldViolation.INVALID_FORMAT)),
            error.violations,
        )
    }

    @Test
    fun `registration and batch results map both ways`() {
        assertEquals(aRegistration(), aRegistration().toDto().toDomain().successValue())

        val result = LocationBatchResult(
            accepted = listOf(locationId(1)),
            duplicates = listOf(locationId(2)),
            rejected = listOf(RejectedLocation("bad", RejectionReason.INVALID_COORDINATES, "latitude")),
            serverTime = T0,
        )
        assertEquals(result, result.toDto().toDomain().successValue())
    }

    @Test
    fun `problem codes become the domain errors the tracker reacts to`() {
        fun problem(code: ErrorCode, max: Int? = null) =
            ProblemDetails(code.problemType, code.name, 0, code = code.name, maxBatchSize = max)

        assertEquals(DomainError.DeviceNotRegistered, problem(ErrorCode.DEVICE_NOT_REGISTERED).toDomainError(404, null))
        assertEquals(DomainError.BatchTooLarge(50), problem(ErrorCode.BATCH_TOO_LARGE, 50).toDomainError(413, null))
        assertEquals(DomainError.Http(429, "RATE_LIMITED", 30), problem(ErrorCode.RATE_LIMITED).toDomainError(429, 30))
        assertEquals(DomainError.Http(502), null.toDomainError(502, null))
    }
}
