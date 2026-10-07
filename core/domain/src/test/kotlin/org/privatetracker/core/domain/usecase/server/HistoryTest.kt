package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.export.HistoryFormat
import org.privatetracker.core.domain.export.writeHistory
import org.privatetracker.core.domain.model.HistoryPeriod
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.Track
import org.privatetracker.core.domain.model.TrackPoint
import org.privatetracker.core.domain.model.dayRange
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aDevice
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.math.sin
import kotlin.test.assertTrue

class HistoryTest {
    private val mexico = ZoneId.of("America/Mexico_City")

    private fun point(minutes: Long, northM: Double = 0.0) =
        TrackPoint(19.4326 + northM / 111_195.0, -99.1332, T0.plus(Duration.ofMinutes(minutes)))

    @Test
    fun `quick ranges count days in the phone's time zone`() {
        // 2026-10-02T18:00Z is noon in Mexico City.
        val today = HistoryPeriod.TODAY.range(T0, mexico)
        assertEquals(Instant.parse("2026-10-02T06:00:00Z"), today.from)
        assertEquals(Instant.parse("2026-10-03T06:00:00Z"), today.to)
        // Rolling ranges reach as far ahead as the server lets a fast tracker clock run.
        assertEquals(T0.plus(Duration.ofMinutes(5)), HistoryPeriod.LAST_7_DAYS.range(T0, mexico).to)
        assertEquals(TimeRange(Instant.parse("2026-10-01T06:00:00Z"), Instant.parse("2026-10-02T06:00:00Z")), HistoryPeriod.YESTERDAY.range(T0, mexico))
        assertEquals(T0.minus(Duration.ofDays(30)), HistoryPeriod.LAST_30_DAYS.range(T0, mexico).from)
        assertEquals(
            TimeRange(Instant.parse("2026-09-28T06:00:00Z"), Instant.parse("2026-10-01T06:00:00Z")),
            dayRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30), mexico),
        )
    }

    @Test
    fun `a route is cut where nothing arrived for more than fifteen minutes, and only stretches add distance`() {
        val track = Track.of(listOf(point(0), point(1, 100.0), point(2, 200.0), point(30, 5_000.0), point(31, 5_100.0)))

        assertEquals(listOf(3, 2), track.segments.map { it.size })
        assertEquals(5, track.pointCount)
        assertEquals(300.0, track.distanceM, 0.5)
        assertEquals(T0, track.start)
        assertEquals(T0.plus(Duration.ofMinutes(31)), track.end)
        assertTrue(Track.of(emptyList()).isEmpty)
    }

    @Test
    fun `a tracker reporting every half hour still draws one line, and a phone resting for hours stays one stretch`() {
        val sparse = Track.of((0L..6L).map { point(it * 30, northM = it * 2_000.0) })
        val resting = Track.of(listOf(point(0), point(1, 100.0), point(180, 120.0), point(181, 300.0)))

        assertEquals(listOf(7), sparse.segments.map { it.size })
        assertEquals(listOf(4), resting.segments.map { it.size })
    }

    @Test
    fun `simplifying drops points that sit on the line and keeps every turn`() {
        val straight = (0L..1_000L).map { point(it * 0 + it / 100, northM = it.toDouble()) }
        val corner = TrackPoint(19.4326 + 1_000 / 111_195.0, -99.1332 + 0.01, T0.plus(Duration.ofMinutes(11)))
        val track = Track(listOf(straight + corner))

        val simplified = track.simplified(toleranceM = 5.0)

        assertEquals(listOf(straight.first(), straight.last(), corner), simplified.segments.single())
        assertEquals(track, track.simplified(toleranceM = 0.0))
    }

    @Test
    fun `thirty days at one fix a minute, standing mostly still, simplify to a small fraction that keeps the roads`() {
        val minutes = 30L * 24 * 60
        // An hour a day along a winding road at 50 m a minute; standing still with a few meters of noise the rest.
        val points = (0 until minutes).map { m ->
            val minuteOfDay = m % 1_440
            val moving = minuteOfDay < 60
            val northM = if (moving) minuteOfDay * 50.0 else 3_000.0 + (m % 7) - 3
            val eastM = if (moving) sin(minuteOfDay / 6.0) * 300.0 else (m % 5) - 2.0
            TrackPoint(19.4326 + northM / 111_195.0, -99.1332 + eastM / 104_900.0, T0.plus(Duration.ofMinutes(m)))
        }

        val simplified = Track.of(points).simplified(toleranceM = 5.0)

        assertEquals(43_200, points.size)
        // The road bends every few minutes, so most moving fixes stay; the still ones go.
        assertTrue(simplified.pointCount in 30 * 20..43_200 / 10, "kept ${simplified.pointCount}")
    }

    @Test
    fun `the track of a device is its points in the range, and an unknown device has none`() = runTest {
        val store = InMemoryServerStore()
        store.insert(aDevice(DEVICE_A))
        store.insert(aDevice(DEVICE_B, name = "Moto de Luis"))
        (1..4).forEach { store.insertIfAbsent(aLocation(it, recordedAt = T0.plusSeconds(it * 60L), receivedAt = T0), null) }
        store.insertIfAbsent(aLocation(9, deviceId = DEVICE_B, recordedAt = T0.plusSeconds(120), receivedAt = T0), null)
        val get = GetDeviceTrack(store, store)

        val track = get(DEVICE_A, TimeRange(T0.plusSeconds(120), T0.plusSeconds(240))).successValue()

        assertEquals(listOf(T0.plusSeconds(120), T0.plusSeconds(180)), track.segments.single().map { it.recordedAt })
        assertEquals(DomainError.DeviceNotFound, get(org.privatetracker.core.domain.model.DeviceId.of("00000000-0000-4000-8000-000000000099"), TimeRange(T0, T0)).failureError())
    }

    @Test
    fun `an export holds every position of the range and names the file after device and days`() = runTest {
        val store = InMemoryServerStore()
        store.insert(aDevice(DEVICE_A))
        (1..3).forEach { store.insertIfAbsent(aLocation(it, recordedAt = T0.plusSeconds(it * 60L), receivedAt = T0), null) }
        val out = StringWriter()

        val count = ExportDeviceHistory(store, store, FakeClock())(DEVICE_A, TimeRange(T0, T0.plusSeconds(3_600)), HistoryFormat.CSV, out).successValue()

        assertEquals(3, count)
        assertEquals(4, out.toString().trimEnd().lines().size)
        val week = dayRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), mexico)
        assertEquals("PrivateTracker-Pixel_de_Ana-2026-10-01_2026-10-07.gpx", historyFileName("Pixel de Ana", week, HistoryFormat.GPX, mexico))
        assertEquals("PrivateTracker-dispositivo-2026-10-02.csv", historyFileName("../..", HistoryPeriod.TODAY.range(T0, mexico), HistoryFormat.CSV, mexico))
    }

    @Test
    fun `GPX is version 1_1 with one segment per stretch, plain numbers and an escaped name`() {
        val locations = listOf(
            aLocation(1, recordedAt = T0),
            aLocation(2, latitude = 0.0001, recordedAt = T0.plusSeconds(60)),
            aLocation(3, recordedAt = T0.plus(Duration.ofHours(1))),
        )
        val out = StringWriter()

        writeHistory(HistoryFormat.GPX, "Ana & <Luis>", locations, T0, out)

        val gpx = out.toString()
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"PrivateTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">"))
        assertTrue("<name>Ana &amp; &lt;Luis&gt;</name>" in gpx)
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertTrue("<trkpt lat=\"19.4326\" lon=\"-99.1332\"><ele>2240</ele><time>2026-10-02T18:00:00Z</time></trkpt>" in gpx, gpx)
        assertTrue("lat=\"0.0001\"" in gpx)
        assertFalse("E-" in gpx)
    }

    @Test
    fun `CSV follows RFC 4180 and never lets a spreadsheet run a tracker's text as a formula`() {
        val tricky = aLocation(1).copy(provider = "=HYPERLINK(\"x\",\"y\")", isMock = true)
        val out = StringWriter()

        writeHistory(HistoryFormat.CSV, "Pixel", listOf(tricky, aLocation(2).copy(provider = null, accuracyM = null)), T0, out)

        val lines = out.toString().split("\r\n")
        assertEquals("recorded_at,latitude,longitude,accuracy_m,altitude_m,speed_mps,bearing_deg,provider,battery_pct,is_mock,location_id", lines[0])
        assertEquals(
            "2026-10-02T18:00:00Z,19.4326,-99.1332,8.5,2240,1.2,87,\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\",76,true,${locationId(1).value}",
            lines[1],
        )
        assertEquals("2026-10-02T18:00:00Z,19.4326,-99.1332,,2240,1.2,87,,76,false,${locationId(2).value}", lines[2])
        assertEquals("", lines.last())
    }
}
