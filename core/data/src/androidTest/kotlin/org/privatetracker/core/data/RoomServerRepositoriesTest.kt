package org.privatetracker.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.data.repository.RoomSessionRepository
import org.privatetracker.core.data.repository.RoomTransactionRunner
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.domain.model.DeviceWithLastLocation
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.PurgeExpiredLocations
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration

/** The server use cases over a real (in-memory) SQLite database, the same scenarios as the JVM tests. */
@RunWith(AndroidJUnit4::class)
class RoomServerRepositoriesTest {
    private lateinit var database: ServerDatabase
    private lateinit var devices: RoomDeviceRepository
    private lateinit var sessions: RoomSessionRepository
    private lateinit var register: RegisterOrUpdateDevice
    private lateinit var ingest: IngestLocationBatch
    private lateinit var purge: PurgeExpiredLocations
    private lateinit var closeInactive: CloseInactiveSessions
    private lateinit var remove: RemoveDevice
    private val clock = FakeClock()
    private val config = InMemoryServerConfigRepository(ServerConfig(retentionDays = 1))

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java).build()
        devices = RoomDeviceRepository(database.devices())
        sessions = RoomSessionRepository(database.sessions(), database.devices())
        val locations = RoomLocationRepository(database.locations(), database.devices(), database.sessions())
        val transactions = RoomTransactionRunner(database)
        val sessionTracker = SessionTracker(sessions, SequentialIdGenerator())
        register = RegisterOrUpdateDevice(devices, config, sessionTracker, transactions, clock)
        ingest = IngestLocationBatch(devices, locations, sessions, config, sessionTracker, LocationValidator(clock), transactions, clock)
        purge = PurgeExpiredLocations(locations, config, clock)
        closeInactive = CloseInactiveSessions(sessions, sessionTracker, config, transactions, clock)
        remove = RemoveDevice(devices)
    }

    @After
    fun tearDown() = database.close()

    private fun rowCount(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun ingestStoresEachLocationOnceAndTracksTheNewest() = runTest {
        register(aRegistration(), "10.0.0.2").successValue()
        clock.advanceBy(Duration.ofSeconds(30))
        val batch = listOf(aLocation(1, recordedAt = T0), aLocation(2, recordedAt = T0.plusSeconds(20)))

        val first = ingest(DEVICE_A, batch, "10.0.0.2").successValue()
        val resent = ingest(DEVICE_A, batch, "10.0.0.2").successValue()

        assertEquals(listOf(locationId(1), locationId(2)), first.accepted)
        assertEquals(listOf(locationId(1), locationId(2)), resent.duplicates)
        assertEquals(2, rowCount("locations"))
        val row = devices.getWithLastLocation(DEVICE_A)!!
        assertEquals(locationId(2), row.lastLocation?.id)
        assertEquals(T0.plusSeconds(30), row.device.lastSeenAt)
        assertEquals(T0.plusSeconds(30), row.lastLocation?.receivedAt)
        assertEquals(2, sessions.findOpen(DEVICE_A)?.locationsReceived)
    }

    @Test
    fun aLateBatchDoesNotMoveTheLastLocationBack() = runTest {
        register(aRegistration(), null)
        ingest(DEVICE_A, listOf(aLocation(2, recordedAt = T0.plusSeconds(60))), null).successValue()

        ingest(DEVICE_A, listOf(aLocation(1, recordedAt = T0)), null).successValue()

        assertEquals(locationId(2), devices.getWithLastLocation(DEVICE_A)?.lastLocation?.id)
    }

    @Test
    fun locationsFromAnUnknownDeviceAreRefused() = runTest {
        assertEquals(DomainError.DeviceNotRegistered, ingest(DEVICE_B, listOf(aLocation()), null).failureError())
        assertEquals(0, rowCount("locations"))
    }

    @Test
    fun purgeKeepsEachDevicesLastLocation() = runTest {
        register(aRegistration(), null)
        ingest(DEVICE_A, listOf(aLocation(1), aLocation(2, recordedAt = T0.plusSeconds(1))), null).successValue()
        clock.advanceBy(Duration.ofDays(2))

        assertEquals(1, purge())

        assertEquals(1, rowCount("locations"))
        assertEquals(locationId(2), devices.getWithLastLocation(DEVICE_A)?.lastLocation?.id)
    }

    @Test
    fun removingADeviceCascadesToItsLocationsAndSessions() = runTest {
        register(aRegistration(), null)
        ingest(DEVICE_A, listOf(aLocation(1), aLocation(2)), null).successValue()

        remove(DEVICE_A).successValue()

        assertEquals(0, rowCount("locations"))
        assertEquals(0, rowCount("device_sessions"))
        assertEquals(DomainError.DeviceNotFound, remove(DEVICE_A).failureError())
    }

    @Test
    fun silentSessionsAreClosedWhenTheirDeviceWentQuiet() = runTest {
        register(aRegistration(), null)
        clock.advanceBy(Duration.ofMinutes(6))

        assertEquals(1, closeInactive())

        assertNull(sessions.findOpen(DEVICE_A))
        val closed = sessions.findRecent(DEVICE_A, limit = 5).single()
        assertEquals(SessionEndReason.INACTIVITY, closed.endReason)
        assertEquals(T0, closed.endedAt)
    }

    @Test
    fun theDeviceListIsRefreshedWhenALocationArrives() = runTest {
        register(aRegistration(), null)
        val emissions = Channel<List<DeviceWithLastLocation>>(Channel.UNLIMITED)
        val collector = launch { devices.observeAllWithLastLocation().collect { emissions.send(it) } }
        assertNull(emissions.receive().single().lastLocation)

        ingest(DEVICE_A, listOf(aLocation(1)), null).successValue()

        var latest = emissions.receive()
        while (latest.single().lastLocation == null) latest = emissions.receive()
        assertEquals(locationId(1), latest.single().lastLocation?.id)
        collector.cancel()
    }

    @Test
    fun registeringAgainKeepsTheCreationDateAndTheSession() = runTest {
        register(aRegistration(), null)
        clock.advanceBy(Duration.ofMinutes(1))

        val again = register(aRegistration(name = "Ana"), null).successValue()

        val device = devices.get(DEVICE_A)
        assertNotNull(device)
        assertTrue(!again.created)
        assertEquals("Ana", device!!.name)
        assertEquals(T0, device.createdAt)
        assertEquals(1, rowCount("device_sessions"))
    }
}
