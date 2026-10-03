package org.privatetracker.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.data.repository.DataStoreIdentityRepository
import org.privatetracker.core.data.repository.DataStoreTrackerConfigRepository
import org.privatetracker.core.data.repository.DataStoreTrackerStateRepository
import org.privatetracker.core.data.repository.RoomOutboxRepository
import org.privatetracker.core.database.TrackerDatabase
import org.privatetracker.core.datastore.IdentityData
import org.privatetracker.core.datastore.JsonSerializer
import org.privatetracker.core.datastore.TrackerConfigData
import org.privatetracker.core.datastore.TrackerStateData
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.locationId
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomOutboxRepositoryTest {
    private lateinit var database: TrackerDatabase
    private lateinit var outbox: RoomOutboxRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        outbox = RoomOutboxRepository(database.outbox())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun locationsLeaveInArrivalOrderAndAFullQueueDropsTheOldest() = runTest {
        val dropped = (1..3).map { outbox.enqueue(aLocation(it), maxSize = 2) }

        assertEquals(listOf(0, 0, 1), dropped)
        assertEquals(listOf(locationId(2), locationId(3)), outbox.peek(10).map { it.location.id })
    }

    @Test
    fun enqueueingTheSameLocationTwiceKeepsOneRow() = runTest {
        outbox.enqueue(aLocation(1), maxSize = 100)
        outbox.enqueue(aLocation(1), maxSize = 100)

        assertEquals(1, outbox.count())
    }

    @Test
    fun failedAttemptsAreRecordedAndAcknowledgedLocationsRemoved() = runTest {
        (1..3).forEach { outbox.enqueue(aLocation(it), maxSize = 100) }

        outbox.markAttempt(listOf(locationId(1), locationId(2)), T0, "NETWORK_UNREACHABLE")
        outbox.remove(listOf(locationId(1)))

        val pending = outbox.peek(10)
        assertEquals(listOf(locationId(2), locationId(3)), pending.map { it.location.id })
        assertEquals(1, pending.first().attempts)
        assertEquals("NETWORK_UNREACHABLE", pending.first().lastError)
        assertEquals(T0, pending.first().lastAttemptAt)
        assertEquals(0, pending.last().attempts)
        assertEquals(2, outbox.observeCount().first())
    }

    @Test
    fun aStoredLocationReadsBackUnchanged() = runTest {
        val location = aLocation(1)
        outbox.enqueue(location, maxSize = 100)

        assertEquals(location, outbox.peek(1).single().location)
    }
}

@RunWith(AndroidJUnit4::class)
class DataStoreRepositoriesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scopes = mutableListOf<CoroutineScope>()

    /** A store on its own file; the same [file] can be reopened after [close] to check persistence. */
    private fun <T> store(file: File, serializer: KSerializer<T>, default: T): DataStore<T> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return DataStoreFactory.create(JsonSerializer(serializer, default), scope = scope, produceFile = { file })
    }

    private fun tempFile() = File(context.cacheDir, "datastore-test-${UUID.randomUUID()}.json")

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    @Test
    fun theDeviceIdIsCreatedOnceAndSurvivesARestart() = runTest {
        val file = tempFile()
        val first = DataStoreIdentityRepository(store(file, IdentityData.serializer(), IdentityData()))
        val id = first.getOrCreate { DEVICE_A }
        assertEquals(DEVICE_A, first.getOrCreate { DEVICE_B })
        scopes.forEach { it.cancel() }

        val reopened = DataStoreIdentityRepository(store(file, IdentityData.serializer(), IdentityData()))

        assertEquals(id, reopened.getOrCreate { DeviceId.of(UUID.randomUUID().toString()) })
    }

    @Test
    fun configUpdatesArePersistedAndObservable() = runTest {
        val repository = DataStoreTrackerConfigRepository(store(tempFile(), TrackerConfigData.serializer(), TrackerConfigData(deviceName = "Pixel")))
        assertEquals("Pixel", repository.get().deviceName)

        repository.update { it.copy(serverUrl = "http://192.168.1.10:8787", intervalSeconds = 30) }

        val observed = repository.observe().first()
        assertEquals("http://192.168.1.10:8787", observed.serverUrl)
        assertEquals(30, observed.intervalSeconds)
    }

    @Test
    fun trackerStateRemembersRegistrationAndTheLastFix() = runTest {
        val repository = DataStoreTrackerStateRepository(store(tempFile(), TrackerStateData.serializer(), TrackerStateData()))
        assertNull(repository.registration())

        repository.setRegistration(TrackerRegistration("http://192.168.1.10:8787", 100, T0))
        repository.setLastRecorded(aLocation(1))

        assertEquals(TrackerRegistration("http://192.168.1.10:8787", 100, T0), repository.registration())
        val last = repository.lastRecorded()!!
        assertEquals(locationId(1), last.id)
        assertEquals(aLocation(1).latitude, last.latitude, 0.0)
        repository.setRegistration(null)
        assertNull(repository.registration())
    }
}
