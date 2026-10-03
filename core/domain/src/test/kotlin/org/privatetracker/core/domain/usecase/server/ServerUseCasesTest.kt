package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.DeviceStatus
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wires the server use cases over in-memory storage, like the DI graph does over Room. */
private class ServerFixture(config: ServerConfig = ServerConfig()) {
    val clock = FakeClock()
    val store = InMemoryServerStore()
    val config = InMemoryServerConfigRepository(config)
    val sessionTracker = SessionTracker(store, SequentialIdGenerator())
    val register = RegisterOrUpdateDevice(store, this.config, sessionTracker, ImmediateTransactionRunner, clock)
    val ingest = IngestLocationBatch(
        store, store, store, this.config, sessionTracker, LocationValidator(clock), ImmediateTransactionRunner, clock,
    )
    val overviews = GetDeviceOverviews(store, this.config, clock)
    val detail = GetDeviceDetail(store, store, this.config, clock)
    val closeInactive = CloseInactiveSessions(store, sessionTracker, this.config, ImmediateTransactionRunner, clock)
    val closeAll = CloseAllSessions(store, sessionTracker, ImmediateTransactionRunner, clock)
    val purge = PurgeExpiredLocations(store, this.config, clock)
    val remove = RemoveDevice(store)
}

class SessionTrackerTest {
    private val fixture = ServerFixture()
    private val limit = Duration.ofMinutes(5)

    @Test
    fun `activity within the limit renews the open session`() = runTest {
        val first = fixture.sessionTracker.recordActivity(DEVICE_A, T0, limit, "10.0.0.2", "0.1.0")
        val renewed = fixture.sessionTracker.recordActivity(DEVICE_A, T0.plusSeconds(300), limit, null, null)

        assertEquals(first.id, renewed.id)
        assertEquals(T0.plusSeconds(300), renewed.lastActivityAt)
        assertEquals("10.0.0.2", renewed.remoteAddress)
    }

    @Test
    fun `activity after the limit closes the old session when it went silent and opens a new one`() = runTest {
        val first = fixture.sessionTracker.recordActivity(DEVICE_A, T0, limit, null, null)
        val second = fixture.sessionTracker.recordActivity(DEVICE_A, T0.plusSeconds(301), limit, null, null)

        val closed = fixture.store.storedSessions.first { it.id == first.id }
        assertEquals(T0, closed.endedAt)
        assertEquals(SessionEndReason.INACTIVITY, closed.endReason)
        assertTrue(second.isOpen)
        assertEquals(second, fixture.store.findOpen(DEVICE_A))
    }
}

class RegisterOrUpdateDeviceTest {
    @Test
    fun `first registration creates the device and opens a session`() = runTest {
        val fixture = ServerFixture()

        val result = fixture.register(aRegistration(name = "  Pixel de Ana "), "10.0.0.2").successValue()

        assertTrue(result.created)
        assertEquals(100, result.maxBatchSize)
        val device = fixture.store.get(DEVICE_A)!!
        assertEquals("Pixel de Ana", device.name)
        assertEquals(T0, device.createdAt)
        assertEquals("10.0.0.2", fixture.store.findOpen(DEVICE_A)?.remoteAddress)
    }

    @Test
    fun `registering again updates the device but keeps its creation date`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(), null)
        fixture.clock.advanceBy(Duration.ofMinutes(1))

        val result = fixture.register(aRegistration(name = "Ana"), null).successValue()

        assertFalse(result.created)
        val device = fixture.store.get(DEVICE_A)!!
        assertEquals("Ana", device.name)
        assertEquals(T0, device.createdAt)
        assertEquals(T0.plusSeconds(60), device.lastSeenAt)
        assertEquals(1, fixture.store.storedSessions.size)
    }

    @Test
    fun `a closed server rejects new devices but still serves known ones`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(id = DEVICE_A), null)
        fixture.config.update { it.copy(acceptNewDevices = false) }

        assertEquals(DomainError.NewDevicesDisabled, fixture.register(aRegistration(id = DEVICE_B), null).failureError())
        fixture.register(aRegistration(id = DEVICE_A), null).successValue()
        assertNull(fixture.store.get(DEVICE_B))
    }

    @Test
    fun `invalid registrations are refused before touching storage`() = runTest {
        val fixture = ServerFixture()

        assertIs<DomainError.UnsupportedProtocolVersion>(fixture.register(aRegistration(protocolVersion = 2), null).failureError())
        assertIs<DomainError.Validation>(fixture.register(aRegistration(name = " "), null).failureError())
        assertNull(fixture.store.get(DEVICE_A))
    }
}

class IngestLocationBatchTest {
    private suspend fun registeredFixture() = ServerFixture().also { it.register(aRegistration(), null) }

    @Test
    fun `locations from an unknown device are refused`() = runTest {
        val fixture = ServerFixture()

        assertEquals(DomainError.DeviceNotRegistered, fixture.ingest(DEVICE_A, listOf(aLocation()), null).failureError())
        assertTrue(fixture.store.storedLocations.isEmpty())
    }

    @Test
    fun `accepted locations are stored with arrival time and become the last location`() = runTest {
        val fixture = registeredFixture()
        fixture.clock.advanceBy(Duration.ofSeconds(30))

        val result = fixture.ingest(
            DEVICE_A,
            listOf(aLocation(1, recordedAt = T0), aLocation(2, recordedAt = T0.plusSeconds(20))),
            "10.0.0.2",
        ).successValue()

        assertEquals(listOf(locationId(1), locationId(2)), result.accepted)
        assertTrue(fixture.store.storedLocations.all { it.location.receivedAt == T0.plusSeconds(30) })
        val row = fixture.store.getWithLastLocation(DEVICE_A)!!
        assertEquals(locationId(2), row.lastLocation?.id)
        assertEquals(T0.plusSeconds(30), row.device.lastSeenAt)
        assertEquals(2, fixture.store.findOpen(DEVICE_A)?.locationsReceived)
    }

    @Test
    fun `resending a batch reports duplicates and stores nothing twice`() = runTest {
        val fixture = registeredFixture()
        val batch = listOf(aLocation(1), aLocation(2))
        fixture.ingest(DEVICE_A, batch, null)

        val again = fixture.ingest(DEVICE_A, batch, null).successValue()

        assertEquals(emptyList(), again.accepted)
        assertEquals(listOf(locationId(1), locationId(2)), again.duplicates)
        assertEquals(2, fixture.store.storedLocations.size)
        assertEquals(2, fixture.store.findOpen(DEVICE_A)?.locationsReceived)
    }

    @Test
    fun `invalid locations are rejected without blocking the rest of the batch`() = runTest {
        val fixture = registeredFixture()

        val result = fixture.ingest(DEVICE_A, listOf(aLocation(1, latitude = 95.0), aLocation(2)), null).successValue()

        assertEquals(listOf(locationId(2)), result.accepted)
        assertEquals(RejectionReason.INVALID_COORDINATES, result.rejected.single().reason)
        assertEquals(locationId(1).value, result.rejected.single().id)
    }

    @Test
    fun `an older batch arriving late does not move the last location back`() = runTest {
        val fixture = registeredFixture()
        fixture.ingest(DEVICE_A, listOf(aLocation(2, recordedAt = T0.plusSeconds(60))), null)

        fixture.ingest(DEVICE_A, listOf(aLocation(1, recordedAt = T0)), null).successValue()

        assertEquals(locationId(2), fixture.store.getWithLastLocation(DEVICE_A)?.lastLocation?.id)
    }

    @Test
    fun `the device id comes from the request path, never from the payload`() = runTest {
        val fixture = registeredFixture()

        fixture.ingest(DEVICE_A, listOf(aLocation(1, deviceId = DEVICE_B)), null).successValue()

        assertEquals(DEVICE_A, fixture.store.storedLocations.single().location.deviceId)
    }

    @Test
    fun `batches over the configured maximum are refused`() = runTest {
        val fixture = registeredFixture()
        fixture.config.update { it.copy(maxBatchSize = 2) }

        val error = fixture.ingest(DEVICE_A, (1..3).map { aLocation(it) }, null).failureError()

        assertEquals(DomainError.BatchTooLarge(2), error)
    }
}

class DeviceQueriesTest {
    @Test
    fun `overviews list the most recently seen devices first with their status`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(id = DEVICE_A), null)
        fixture.clock.advanceBy(Duration.ofMinutes(10))
        fixture.register(aRegistration(id = DEVICE_B, name = "Moto"), null)

        val overviews = fixture.overviews()

        assertEquals(listOf(DEVICE_B, DEVICE_A), overviews.map { it.device.id })
        assertEquals(
            listOf(DeviceStatus.ONLINE, DeviceStatus.STALE),
            overviews.map { it.status },
        )
    }

    @Test
    fun `detail hides a session whose device went silent`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(), null)
        assertTrue(fixture.detail(DEVICE_A).successValue().currentSession != null)

        fixture.clock.advanceBy(Duration.ofMinutes(6))

        assertNull(fixture.detail(DEVICE_A).successValue().currentSession)
        assertEquals(DomainError.DeviceNotFound, fixture.detail(DEVICE_B).failureError())
    }
}

class MaintenanceTest {
    @Test
    fun `purge deletes expired locations but keeps each device's last one`() = runTest {
        val fixture = ServerFixture(ServerConfig(retentionDays = 1))
        fixture.register(aRegistration(), null)
        fixture.ingest(DEVICE_A, listOf(aLocation(1), aLocation(2, recordedAt = T0.plusSeconds(1))), null)
        fixture.clock.advanceBy(Duration.ofDays(2))

        assertEquals(1, fixture.purge())

        assertEquals(listOf(locationId(2)), fixture.store.storedLocations.map { it.location.id })
    }

    @Test
    fun `only sessions silent beyond the threshold are closed`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(id = DEVICE_A), null)
        fixture.clock.advanceBy(Duration.ofMinutes(4))
        fixture.register(aRegistration(id = DEVICE_B), null)
        fixture.clock.advanceBy(Duration.ofMinutes(2))

        assertEquals(1, fixture.closeInactive())

        assertNull(fixture.store.findOpen(DEVICE_A))
        assertTrue(fixture.store.findOpen(DEVICE_B) != null)
    }

    @Test
    fun `stopping the server closes every open session`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(id = DEVICE_A), null)
        fixture.register(aRegistration(id = DEVICE_B), null)

        assertEquals(2, fixture.closeAll())

        assertTrue(fixture.store.storedSessions.all { it.endReason == SessionEndReason.SERVER_STOPPED })
    }

    @Test
    fun `removing a device removes its locations and sessions`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration(), null)
        fixture.ingest(DEVICE_A, listOf(aLocation()), null)

        fixture.remove(DEVICE_A).successValue()

        assertTrue(fixture.store.storedLocations.isEmpty())
        assertTrue(fixture.store.storedSessions.isEmpty())
        assertEquals(DomainError.DeviceNotFound, fixture.remove(DEVICE_A).failureError())
    }
}
