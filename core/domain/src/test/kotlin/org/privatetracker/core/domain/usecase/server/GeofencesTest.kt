package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.geo.destination
import org.privatetracker.core.domain.geo.distanceMeters
import org.privatetracker.core.domain.geo.insideDepthM
import org.privatetracker.core.domain.geo.isSelfIntersecting
import org.privatetracker.core.domain.geo.outline
import org.privatetracker.core.domain.geo.polygonAreaM2
import org.privatetracker.core.domain.geo.signedDistanceToEdge
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEvent
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.GeofenceState
import org.privatetracker.core.domain.model.GeofenceTransition
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.InMemoryGeofences
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.successValue
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CENTER = GeoPoint(19.4326, -99.1332)
private val HOME = GeoPoint(19.4326, -99.1332)

/** A square of [sideM] meters around [center]. */
private fun square(center: GeoPoint, sideM: Double): GeofenceShape.Polygon = rectangle(center, sideM, sideM)

/** A rectangle [widthM] meters from west to east and [heightM] from south to north, around [center]. */
private fun rectangle(center: GeoPoint, widthM: Double, heightM: Double): GeofenceShape.Polygon {
    val n = destination(center, heightM / 2, 0.0).latitude
    val s = destination(center, heightM / 2, 180.0).latitude
    val east = destination(center, widthM / 2, 90.0).longitude
    val west = destination(center, widthM / 2, 270.0).longitude
    return GeofenceShape.Polygon(listOf(GeoPoint(n, west), GeoPoint(n, east), GeoPoint(s, east), GeoPoint(s, west)))
}

private fun aGeofence(shape: GeofenceShape = GeofenceShape.Circle(HOME, 200.0), notifyOnEnter: Boolean = true) =
    Geofence(GeofenceId.of("11111111-1111-4111-8111-111111111111"), "Casa", shape, deviceIds = null, notifyOnEnter = notifyOnEnter, createdAt = T0)

/** A fix [northM] meters north of [HOME], [minutes] after T0, with [accuracyM]. */
private fun fixNorth(n: Int, minutes: Long, northM: Double, accuracyM: Float = 10f): Location {
    val at = destination(HOME, abs(northM), if (northM >= 0) 0.0 else 180.0)
    return aLocation(n, latitude = at.latitude, longitude = at.longitude, accuracyM = accuracyM, recordedAt = T0.plus(Duration.ofMinutes(minutes)))
}

class GeometryTest {
    @Test
    fun `distances to the edge are positive inside and negative outside, for circles and polygons`() {
        val circle = GeofenceShape.Circle(CENTER, 200.0)
        assertEquals(200.0, signedDistanceToEdge(circle, CENTER), 0.01)
        assertEquals(-100.0, signedDistanceToEdge(circle, destination(CENTER, 300.0, 45.0)), 0.5)

        val polygon = square(CENTER, 400.0)
        assertEquals(200.0, signedDistanceToEdge(polygon, CENTER), 1.0)
        assertEquals(50.0, signedDistanceToEdge(polygon, destination(CENTER, 150.0, 90.0)), 1.0)
        assertEquals(-50.0, signedDistanceToEdge(polygon, destination(CENTER, 250.0, 180.0)), 1.0)
        assertEquals(160_000.0, polygonAreaM2(polygon.vertices), 1_000.0)
    }

    @Test
    fun `a shape is as deep as the largest circle it holds, however large its area`() {
        assertEquals(200.0, insideDepthM(GeofenceShape.Circle(CENTER, 200.0)))
        assertEquals(200.0, insideDepthM(square(CENTER, 400.0)), 0.6)
        // 9,000 m², more than a 50 m circle, yet nowhere more than 15 m from the edge.
        assertEquals(15.0, insideDepthM(rectangle(CENTER, 300.0, 30.0)), 0.6)
        assertEquals(60.0, insideDepthM(rectangle(CENTER, 8_000.0, 120.0)), 0.6)
        // An L: two 100 m arms, 400 m long. The deepest point is where they meet.
        val (sw, nw) = destination(CENTER, 0.0, 0.0) to destination(CENTER, 400.0, 0.0)
        val l = listOf(
            sw,
            GeoPoint(sw.latitude, destination(sw, 400.0, 90.0).longitude),
            GeoPoint(destination(sw, 100.0, 0.0).latitude, destination(sw, 400.0, 90.0).longitude),
            GeoPoint(destination(sw, 100.0, 0.0).latitude, destination(sw, 100.0, 90.0).longitude),
            GeoPoint(nw.latitude, destination(sw, 100.0, 90.0).longitude),
            nw,
        )
        assertTrue(insideDepthM(GeofenceShape.Polygon(l)) in 50.0..72.0)
        assertEquals(0.0, insideDepthM(GeofenceShape.Polygon(listOf(sw, nw, destination(CENTER, 800.0, 0.0)))), 0.01)
    }

    @Test
    fun `a circle is drawn with points on its edge, and a bow tie is not a polygon`() {
        val points = outline(GeofenceShape.Circle(CENTER, 500.0))

        assertEquals(64, points.size)
        assertTrue(points.all { abs(distanceMeters(CENTER.latitude, CENTER.longitude, it.latitude, it.longitude) - 500.0) < 1.0 })
        val (a, b, c, d) = square(CENTER, 400.0).vertices
        assertTrue(isSelfIntersecting(listOf(a, c, b, d)))
        assertTrue(!isSelfIntersecting(listOf(a, b, c, d)))
    }
}

class CrossingDetectorTest {
    private val fence = aGeofence()

    private fun run(locations: List<Location>, start: GeofenceState? = null): Pair<GeofenceState?, List<Crossing>> {
        var state = start
        val crossings = mutableListOf<Crossing>()
        for (location in locations) {
            val (next, crossing) = CrossingDetector.step(state, fence, DEVICE_A, location)
            state = next
            crossing?.let(crossings::add)
        }
        return state to crossings
    }

    @Test
    fun `the first side a device is seen on raises no event, walking in and out raises one each`() {
        // Outside 600 m north, walking in to the center and back out, a fix a minute.
        val path = listOf(600.0, 550.0, 500.0, 400.0, 300.0, 150.0, 50.0, 0.0, 0.0, 0.0, 100.0, 250.0, 350.0, 450.0, 550.0)
        val (state, crossings) = run(path.mapIndexed { i, m -> fixNorth(i + 1, i.toLong(), m) })

        assertEquals(listOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT), crossings.map { it.transition })
        // When the first fix of each run on the new side was recorded, and the third, which confirmed it.
        assertEquals(listOf(T0.plus(Duration.ofMinutes(5)), T0.plus(Duration.ofMinutes(11))), crossings.map { it.occurredAt })
        assertEquals(listOf(T0.plus(Duration.ofMinutes(7)), T0.plus(Duration.ofMinutes(13))), crossings.map { it.confirmedAt })
        assertEquals(false, state?.inside)
    }

    @Test
    fun `a phone standing right on the edge for a day, its fixes wandering across it, never flaps`() {
        val random = java.util.Random(7)
        // Android's accuracy is the 68 % radius: about 1.5 standard deviations of one axis.
        val fixes = (0 until 24 * 60).map { minute ->
            val accuracy = 8.0 + random.nextDouble() * 32.0
            val error = random.nextGaussian() * accuracy / 1.515
            fixNorth(minute + 1, minute.toLong(), 200.0 + error, accuracy.toFloat())
        }
        // Known to be inside when it sat down there.
        val start = GeofenceState(fence.id, DEVICE_A, inside = true, null, 0, null, T0.minusSeconds(1))

        val (_, crossings) = run(fixes, start)

        assertEquals(emptyList(), crossings)
    }

    @Test
    fun `an unsure fix breaks a run, a poor fix says nothing, and a late fix changes nothing`() {
        val inside = GeofenceState(fence.id, DEVICE_A, inside = true, null, 0, null, T0)
        // Two clearly outside, one on the edge, two outside: no three in a row.
        val (state, crossings) = run(
            listOf(fixNorth(1, 1, 400.0), fixNorth(2, 2, 400.0), fixNorth(3, 3, 205.0), fixNorth(4, 4, 400.0), fixNorth(5, 5, 400.0)),
            inside,
        )
        assertEquals(emptyList(), crossings)
        assertEquals(true, state?.inside)

        assertEquals(Side.UNSURE, CrossingDetector.sideOf(fence.shape, fixNorth(6, 6, 2_000.0, accuracyM = 150f)))
        val (unchanged, late) = CrossingDetector.step(state, fence, DEVICE_A, fixNorth(7, 0, 900.0))
        assertEquals(state, unchanged)
        assertNull(late)
    }

    @Test
    fun `the margin never swallows a small area`() {
        val small = aGeofence(GeofenceShape.Circle(HOME, 60.0))

        // 40 m accuracy would ask for 60 m of margin; it is capped at half the radius.
        assertEquals(Side.INSIDE, CrossingDetector.sideOf(small.shape, fixNorth(1, 0, 10.0, accuracyM = 40f)))
        assertEquals(Side.UNSURE, CrossingDetector.sideOf(small.shape, fixNorth(2, 0, 45.0, accuracyM = 40f)))

        // A long street, 120 m wide: by its area it is huge, by its depth it is not. 50 m accuracy would
        // ask for 75 m of margin, more than the 60 m from its middle to the edge; half of those 60 is used.
        val street = rectangle(HOME, 2_000.0, 120.0)
        assertEquals(Side.INSIDE, CrossingDetector.sideOf(street, fixNorth(3, 0, 0.0, accuracyM = 50f)))
        assertEquals(Side.UNSURE, CrossingDetector.sideOf(street, fixNorth(4, 0, 40.0, accuracyM = 50f)))
    }
}

class SaveGeofenceTest {
    private val clock = FakeClock()
    private val geofences = InMemoryGeofences()
    private val save = SaveGeofence(geofences, SequentialIdGenerator(), clock)
    private val draft = GeofenceDraft(null, "Casa", GeofenceShape.Circle(HOME, 200.0), null, notifyOnEnter = true, notifyOnExit = true)

    @Test
    fun `a new geofence gets an id and its creation time, which an edit keeps`() = runTest {
        val saved = save(draft).successValue()
        clock.advanceBy(Duration.ofHours(1))

        val edited = save(draft.copy(id = saved.id, name = "  Casa nueva ")).successValue()

        assertEquals(T0, edited.createdAt)
        assertEquals("Casa nueva", edited.name)
        assertEquals(DomainError.GeofenceNotFound, save(draft.copy(id = GeofenceId.of("22222222-2222-4222-8222-222222222222"))).failureError())
    }

    @Test
    fun `what is known about each device survives an edit that leaves the edge in place`() = runTest {
        val id = save(draft).successValue().id
        val edit = draft.copy(id = id)
        fun knowsAbout() = geofences.storedStates.keys.map { it.second }.toSet()
        suspend fun remember() {
            geofences.saveState(GeofenceState(id, DEVICE_A, true, null, 0, null, T0))
            geofences.saveState(GeofenceState(id, DEVICE_B, false, null, 0, null, T0))
        }
        remember()

        // A device 2 minutes from home must not lose its arrival because the owner renamed the place.
        save(edit.copy(name = "Casa nueva", notifyOnExit = false)).successValue()
        assertEquals(setOf(DEVICE_A, DEVICE_B), knowsAbout())

        save(edit.copy(deviceIds = setOf(DEVICE_A))).successValue()
        assertEquals(setOf(DEVICE_A), knowsAbout(), "a device no longer watched is forgotten")

        remember()
        save(edit.copy(shape = GeofenceShape.Circle(HOME, 300.0))).successValue()
        assertEquals(emptySet(), knowsAbout(), "the edge moved: detection starts over")
    }

    @Test
    fun `names, radii, vertices and device choices are checked`() {
        fun violations(d: GeofenceDraft) = validateGeofence(d).map { it.field to it.rule }
        val square = square(HOME, 400.0)

        assertEquals(listOf("name" to FieldViolation.REQUIRED), violations(draft.copy(name = " ")))
        assertEquals(listOf("radius" to FieldViolation.OUT_OF_RANGE), violations(draft.copy(shape = GeofenceShape.Circle(HOME, 20.0))))
        assertEquals(emptyList(), violations(draft.copy(shape = square)))
        assertEquals(listOf("vertices" to FieldViolation.OUT_OF_RANGE), violations(draft.copy(shape = GeofenceShape.Polygon(square.vertices.take(2)))))
        val (a, b, c, d) = square.vertices
        assertEquals(listOf("vertices" to FieldViolation.INVALID_FORMAT), violations(draft.copy(shape = GeofenceShape.Polygon(listOf(a, c, b, d)))))
        assertEquals(listOf("vertices" to FieldViolation.OUT_OF_RANGE), violations(draft.copy(shape = square(HOME, 30.0))))
        // It must hold a 50 m circle: a large area is not enough when it is thin.
        assertEquals(listOf("vertices" to FieldViolation.OUT_OF_RANGE), violations(draft.copy(shape = rectangle(HOME, 300.0, 30.0))))
        assertEquals(listOf("vertices" to FieldViolation.OUT_OF_RANGE), violations(draft.copy(shape = square(HOME, 90.0))))
        assertEquals(emptyList(), violations(draft.copy(shape = rectangle(HOME, 1_000.0, 110.0))))
        assertEquals(listOf("devices" to FieldViolation.REQUIRED), violations(draft.copy(deviceIds = emptySet())))
    }

    @Test
    fun `old events go with the positions they came from, counted from when they reached the server`() = runTest {
        val config = InMemoryServerConfigRepository()
        val id = save(draft).successValue().id
        geofences.addEvent(GeofenceEvent(id, DEVICE_A, GeofenceTransition.ENTER, T0, T0, true))
        // From a tracker that was offline for days: it happened long ago, but arrived late.
        val backlog = T0.plus(Duration.ofDays(29))
        geofences.addEvent(GeofenceEvent(id, DEVICE_A, GeofenceTransition.EXIT, T0.plus(Duration.ofHours(1)), backlog, false))
        clock.current = T0.plus(Duration.ofDays(31))

        assertEquals(1, PurgeGeofenceEvents(geofences, config, clock)())
        assertEquals(listOf(GeofenceTransition.EXIT), geofences.storedEvents.map { it.transition })
    }
}
