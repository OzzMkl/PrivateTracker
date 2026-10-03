package org.privatetracker.core.domain.validation

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationValidatorTest {
    private val validator = LocationValidator(FakeClock(T0))

    private fun reasonFor(transform: (Location) -> Location) = validator.validate(transform(aLocation()))?.reason

    @Test
    fun `a plausible location passes`() {
        assertNull(validator.validate(aLocation()))
    }

    @Test
    fun `coordinates outside WGS84 ranges or not finite are rejected`() {
        assertEquals(RejectionReason.INVALID_COORDINATES, reasonFor { it.copy(latitude = 90.0001) })
        assertEquals(RejectionReason.INVALID_COORDINATES, reasonFor { it.copy(longitude = -180.5) })
        assertEquals(RejectionReason.INVALID_COORDINATES, reasonFor { it.copy(latitude = Double.NaN) })
    }

    @Test
    fun `negative accuracy is rejected`() {
        assertEquals(RejectionReason.INVALID_ACCURACY, reasonFor { it.copy(accuracyM = -1f) })
    }

    @Test
    fun `optional fields out of range are rejected`() {
        assertEquals(RejectionReason.INVALID_FIELD, reasonFor { it.copy(bearingDeg = 360f) })
        assertEquals(RejectionReason.INVALID_FIELD, reasonFor { it.copy(batteryPct = 101) })
        assertEquals(RejectionReason.INVALID_FIELD, reasonFor { it.copy(speedMps = -0.1f) })
    }

    @Test
    fun `timestamps up to five minutes in the future are tolerated`() {
        assertNull(reasonFor { it.copy(recordedAt = T0.plus(Duration.ofMinutes(5))) })
        assertEquals(
            RejectionReason.TIMESTAMP_IN_FUTURE,
            reasonFor { it.copy(recordedAt = T0.plus(Duration.ofMinutes(5)).plusSeconds(1)) },
        )
    }
}

class TrackerConfigValidatorTest {
    private val valid = TrackerConfig(serverUrl = "http://192.168.1.10:8787", deviceName = "Pixel de Ana")

    @Test
    fun `a complete configuration is valid`() {
        assertEquals(emptyList(), TrackerConfigValidator.validate(valid))
    }

    @Test
    fun `defaults are incomplete until server and name are set`() {
        val fields = TrackerConfigValidator.validate(TrackerConfig()).associate { it.field to it.rule }
        assertEquals(mapOf("serverUrl" to FieldViolation.REQUIRED, "deviceName" to FieldViolation.REQUIRED), fields)
    }

    @Test
    fun `accepts base URLs only`() {
        listOf("http://192.168.1.10:8787", "https://tracker.example.org", "http://[fe80::1]:8787/", "http://localhost:8787")
            .forEach { assertNull(TrackerConfigValidator.validateServerUrl(it), it) }
        listOf("ftp://host", "192.168.1.10:8787", "http://host:8787/api", "http://host?x=1", "http://user@host", "http://")
            .forEach { assertEquals(FieldViolation.INVALID_FORMAT, TrackerConfigValidator.validateServerUrl(it)?.rule, it) }
    }

    @Test
    fun `numeric settings must stay within their ranges`() {
        val fields = TrackerConfigValidator.validate(
            valid.copy(intervalSeconds = 9, batchSize = 101, maxQueueSize = 99, minDistanceM = -1f),
        ).map { it.field }
        assertEquals(listOf("intervalSeconds", "minDistanceM", "batchSize", "maxQueueSize"), fields)
    }

    @Test
    fun `names longer than 64 characters are rejected`() {
        val violation = TrackerConfigValidator.validate(valid.copy(deviceName = "x".repeat(65))).single()
        assertEquals(FieldViolation("deviceName", FieldViolation.TOO_LONG), violation)
    }
}

class ServerConfigValidatorTest {
    @Test
    fun `defaults are valid`() {
        assertEquals(emptyList(), ServerConfigValidator.validate(ServerConfig()))
    }

    @Test
    fun `privileged ports and malformed bind addresses are rejected`() {
        val fields = ServerConfigValidator.validate(ServerConfig(port = 80, bindAddress = "256.1.1.1")).map { it.field }
        assertEquals(listOf("port", "bindAddress"), fields)
    }

    @Test
    fun `bind address must be an IP literal`() {
        assertTrue(ServerConfigValidator.validate(ServerConfig(bindAddress = "::")).isEmpty())
        assertTrue(ServerConfigValidator.validate(ServerConfig(bindAddress = "127.0.0.1")).isEmpty())
        assertEquals("bindAddress", ServerConfigValidator.validate(ServerConfig(bindAddress = "localhost")).single().field)
    }
}
