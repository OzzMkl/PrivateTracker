package org.privatetracker.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomGeofenceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.data.repository.RoomSessionRepository
import org.privatetracker.core.data.repository.RoomTransactionRunner
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEvent
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.GeofenceState
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.RecordingGeofenceAlerts
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aDevice
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.server.EvaluateGeofences
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration

/** Geofences in a real (in-memory) server.db: what is stored, what cascades, and crossings found during ingest. */
@RunWith(AndroidJUnit4::class)
class RoomGeofenceRepositoryTest {
    private lateinit var database: ServerDatabase
    private lateinit var devices: RoomDeviceRepository
    private lateinit var geofences: RoomGeofenceRepository
    private val home = GeoPoint(19.4326, -99.1332)
    private val circle = Geofence(
        GeofenceId.of("11111111-1111-4111-8111-111111111111"), "Casa", GeofenceShape.Circle(home, 200.0), deviceIds = null, createdAt = T0,
    )
    private val polygon = Geofence(
        GeofenceId.of("22222222-2222-4222-8222-222222222222"),
        "Escuela",
        GeofenceShape.Polygon(listOf(GeoPoint(19.0, -99.0), GeoPoint(19.0, -98.99), GeoPoint(19.01, -98.99))),
        deviceIds = setOf(DEVICE_B),
        notifyOnExit = false,
        createdAt = T0,
    )

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java).build()
        devices = RoomDeviceRepository(database)
        geofences = RoomGeofenceRepository(database, database.geofences(), database.devices())
        devices.insert(aDevice(DEVICE_A).copy(approval = DeviceApproval.APPROVED))
        devices.insert(aDevice(DEVICE_B, name = "Moto de Luis"))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun circlesAndPolygonsComeBackAsSaved() = runTest {
        geofences.save(circle, keepStates = false)
        geofences.save(polygon, keepStates = false)

        assertEquals(listOf(circle, polygon), geofences.all())
        assertEquals(polygon, geofences.get(polygon.id))
        assertEquals(listOf(circle, polygon), geofences.observeAll().first())
    }

    @Test
    fun editingKeepsEventsAndStatesUnlessToldOtherwiseAndDeletingCascades() = runTest {
        geofences.save(circle, keepStates = false)
        val known = GeofenceState(circle.id, DEVICE_A, true, null, 0, null, T0)
        geofences.saveState(known)
        geofences.saveState(GeofenceState(circle.id, DEVICE_B, false, null, 0, null, T0))
        geofences.addEvent(GeofenceEvent(circle.id, DEVICE_A, GeofenceTransition.ENTER, T0, T0, alerted = true))

        geofences.save(circle.copy(name = "Casa nueva"), keepStates = true)
        assertEquals(known, geofences.state(circle.id, DEVICE_A))
        geofences.save(circle.copy(name = "Casa nueva", deviceIds = setOf(DEVICE_A)), keepStates = true)
        assertEquals(known, geofences.state(circle.id, DEVICE_A))
        assertNull(geofences.state(circle.id, DEVICE_B))
        geofences.save(circle.copy(name = "Casa nueva", shape = GeofenceShape.Circle(home, 300.0)), keepStates = false)
        assertNull(geofences.state(circle.id, DEVICE_A))

        val event = geofences.observeEvents(10).first().single()
        assertEquals("Casa nueva" to "Pixel de Ana", event.geofenceName to event.deviceName)

        assertTrue(geofences.delete(circle.id))
        assertTrue(geofences.observeEvents(10).first().isEmpty())
    }

    @Test
    fun eventsArePurgedByWhenTheServerDetectedThem() = runTest {
        geofences.save(circle, keepStates = false)
        val day = Duration.ofDays(1)
        geofences.addEvent(GeofenceEvent(circle.id, DEVICE_A, GeofenceTransition.ENTER, T0, T0, alerted = true))
        // Happened before the first one, but arrived two days later.
        geofences.addEvent(GeofenceEvent(circle.id, DEVICE_A, GeofenceTransition.EXIT, T0.minus(day), T0.plus(day.multipliedBy(2)), alerted = false))

        assertEquals(1, geofences.deleteEventsBefore(T0.plus(day)))

        val left = geofences.observeEvents(10).first().single().event
        assertEquals(GeofenceTransition.EXIT to T0.plus(day.multipliedBy(2)), left.transition to left.detectedAt)
    }

    @Test
    fun removingADeviceTakesItsStatesEventsAndChoicesWithIt() = runTest {
        geofences.save(polygon, keepStates = false)
        geofences.saveState(GeofenceState(polygon.id, DEVICE_B, false, true, 1, T0, T0))
        geofences.addEvent(GeofenceEvent(polygon.id, DEVICE_B, GeofenceTransition.EXIT, T0, T0, alerted = false))

        devices.delete(DEVICE_B, T0)

        assertNull(geofences.state(polygon.id, DEVICE_B))
        assertTrue(geofences.observeEvents(10).first().isEmpty())
        assertEquals(emptySet<Any>(), geofences.get(polygon.id)?.deviceIds)
    }

    @Test
    fun ingestingPositionsFindsTheCrossingInTheSameTransactionAndAlertsAfterIt() = runTest {
        geofences.save(circle, keepStates = false)
        val clock = FakeClock(T0.plus(Duration.ofMinutes(10)))
        val sessions = RoomSessionRepository(database.sessions(), database.devices())
        val alerts = RecordingGeofenceAlerts()
        val ingest = IngestLocationBatch(
            devices, RoomLocationRepository(database.locations(), database.devices(), database.sessions()), sessions,
            InMemoryServerConfigRepository(ServerConfig()), SessionTracker(sessions, SequentialIdGenerator()), LocationValidator(clock),
            RoomTransactionRunner(database), clock, EvaluateGeofences(geofences, alerts, clock), alerts,
        )
        fun at(n: Int, minutes: Long, northM: Double) =
            aLocation(n, latitude = 19.4326 + northM / 111_195.0, accuracyM = 10f, recordedAt = T0.plus(Duration.ofMinutes(minutes)))

        ingest(DEVICE_A, (0..2).map { at(it + 1, it.toLong(), 600.0) }, null).successValue()
        ingest(DEVICE_A, (3..5).map { at(it + 1, it.toLong(), 0.0) }, null).successValue()

        assertEquals(GeofenceTransition.ENTER, alerts.alerted.single().event.transition)
        assertEquals(true, geofences.state(circle.id, DEVICE_A)?.inside)
        assertEquals(1, geofences.observeEvents(10).first().size)
    }
}
