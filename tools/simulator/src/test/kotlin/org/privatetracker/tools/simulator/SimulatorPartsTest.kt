package org.privatetracker.tools.simulator

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.domain.geo.distanceMeters
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorPartsTest {

    @Test
    fun `durations read as people write them`() {
        assertEquals(Duration.ofMillis(500), parseDuration("500ms"))
        assertEquals(Duration.ofSeconds(30), parseDuration("30s"))
        assertEquals(Duration.ofHours(24), parseDuration("24H"))
        assertEquals(Duration.ofMinutes(90), parseDuration("1h30m"))
        listOf("", "0s", "-5m", "soon", "1.5h").forEach { assertFailsWith<UsageException>(it) { parseDuration(it) } }
        assertEquals("1h 05m 03s", formatDuration(Duration.ofSeconds(3903)))
        assertEquals("250 ms", formatDuration(Duration.ofMillis(250)))
    }

    @Test
    fun `retries back off exponentially, honour Retry-After, and never wait past the cap`() {
        val policy = RetryPolicy(initial = Duration.ofSeconds(30), max = Duration.ofMinutes(5))
        assertEquals(Duration.ofSeconds(30), policy.delayFor(1, null))
        assertEquals(Duration.ofSeconds(120), policy.delayFor(3, null))
        assertEquals(Duration.ofMinutes(5), policy.delayFor(40, null))
        assertEquals(Duration.ofSeconds(7), policy.delayFor(1, retryAfterSeconds = 7))
        assertEquals(Duration.ofMinutes(5), policy.delayFor(1, retryAfterSeconds = 3_600))
    }

    @Test
    fun `the command line rejects what it does not know`() {
        val cli = CommandLine(listOf("--trackers", "5", "--pull", "--typo", "x"), switches = setOf("--pull"))
        assertEquals(5, cli.int("--trackers", 10, 1..10))
        assertTrue(cli.flag("--pull"))
        assertFailsWith<UsageException> { cli.rejectUnknown() }
        assertFailsWith<UsageException> { CommandLine(listOf("--trackers")) }
        assertFailsWith<UsageException> { CommandLine(listOf("--trackers", "0")).int("--trackers", 10, 1..10) }
        assertFailsWith<UsageException> { CommandLine(listOf("--drop", "1")).probability("--drop") }
    }

    @Test
    fun `simulated fixes always pass the validation the app and the server apply`() {
        val center = GeoPoint(19.4326, -99.1332)
        val validator = LocationValidator({ T0.plus(Duration.ofDays(30)) })
        val battery = SimulatedBattery(Random(7))
        val deviceId = DeviceId.of(UUID.randomUUID().toString())
        repeat(5) { seed ->
            val route = RandomRoute(center, Random(seed), radiusM = 2_000.0)
            var time = T0
            repeat(5_000) {
                time = time.plusSeconds(60)
                val fix = route.next(time)
                val location = Location(
                    id = LocationId.of(UUID.randomUUID().toString()),
                    deviceId = deviceId,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracyM,
                    speedMps = fix.speedMps,
                    bearingDeg = fix.bearingDeg,
                    provider = fix.provider,
                    batteryPct = battery.currentLevelPct(),
                    isMock = fix.isMock,
                    recordedAt = fix.recordedAt,
                )
                assertNull(validator.validate(location), "seed $seed step $it: $location")
                // Heading home starts at the radius; one fix at driving speed can overshoot it by under 1 km.
                val distance = distanceMeters(center.latitude, center.longitude, fix.latitude, fix.longitude)
                assertTrue(distance < 3_000.0, "seed $seed step $it is $distance m away")
            }
        }
    }

    @Test
    fun `a full outbox drops its oldest and says which`() = runTest {
        val dropped = mutableListOf<Location>()
        val outbox = MemoryOutbox { dropped += it }

        repeat(5) { outbox.enqueue(aLocation(n = it + 1), maxSize = 3) }

        assertEquals(listOf(aLocation(n = 1).id, aLocation(n = 2).id), dropped.map { it.id })
        assertEquals(3, outbox.count())
        assertEquals(listOf(aLocation(n = 3).id, aLocation(n = 4).id), outbox.peek(2).map { it.location.id })
        outbox.remove(listOf(aLocation(n = 4).id))
        assertEquals(listOf(aLocation(n = 3).id, aLocation(n = 5).id), outbox.peek(10).map { it.location.id })
        outbox.markAttempt(listOf(aLocation(n = 3).id), T0, "NETWORK_TIMEOUT")
        assertEquals(1, outbox.peek(1).single().attempts)
    }
}
