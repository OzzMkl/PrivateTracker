package org.privatetracker.core.data

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aDevice
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.server.GetDeviceTrack
import java.time.Duration
import java.util.UUID
import kotlin.math.sin
import kotlin.time.measureTimedValue

/**
 * The 0.6 exit criterion, on the data side: thirty days of one device at a fix a minute (43,200 rows)
 * come out of server.db as a route ready to draw. A file database, not an in-memory one, so the
 * disk counts too; the map's own drawing is timed in the app (DeviceHistory in logcat).
 */
@RunWith(AndroidJUnit4::class)
class HistoryPerformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: ServerDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(NAME)
        database = Room.databaseBuilder(context, ServerDatabase::class.java, NAME).build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun thirtyDaysOfOneDeviceBecomeADrawableRouteWellUnderTwoSeconds() = runTest {
        val devices = RoomDeviceRepository(database.devices())
        devices.insert(aDevice(DEVICE_A))
        devices.insert(aDevice(DEVICE_B, name = "Otro"))
        insertMonth(deviceKey = 1, minutes = MONTH_MINUTES)
        // Another device's month in the same table, which the range scan must skip.
        insertMonth(deviceKey = 2, minutes = MONTH_MINUTES)
        val locations = RoomLocationRepository(database.locations(), database.devices(), database.sessions())
        val range = TimeRange(T0, T0.plus(Duration.ofDays(30)))

        val (track, readTime) = measureTimedValue { GetDeviceTrack(devices, locations)(DEVICE_A, range).successValue() }
        val (drawable, simplifyTime) = measureTimedValue { track.simplified(toleranceM = 10.0) }

        Log.i("HistoryPerformance", "43200 filas: lectura ${readTime.inWholeMilliseconds} ms, simplificación ${simplifyTime.inWholeMilliseconds} ms, ${drawable.pointCount} puntos a dibujar")
        assertEquals(MONTH_MINUTES, track.pointCount)
        assertTrue("${drawable.pointCount} points to draw", drawable.pointCount < track.pointCount / 10)
        assertTrue("took ${readTime + simplifyTime}", (readTime + simplifyTime).inWholeMilliseconds < 1_000)
    }

    @Test
    fun theHistoryQueryScansTheDeviceAndTimeIndex() {
        val plan = database.openHelper.readableDatabase.query(
            """
            EXPLAIN QUERY PLAN SELECT l.latitude, l.longitude, l.recorded_at FROM locations l
            WHERE l.device_id = (SELECT id FROM devices WHERE device_uid = ?)
              AND l.recorded_at >= ? AND l.recorded_at < ? ORDER BY l.recorded_at
            """,
            arrayOf<Any>(DEVICE_A.value, 0L, 1L),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) } }

        assertTrue(plan.toString(), plan.any { "index_locations_device_id_recorded_at" in it })
        assertTrue(plan.toString(), plan.none { "TEMP B-TREE" in it })
    }

    /** One fix a minute: an hour each day along a winding road, standing still with a few meters of noise the rest. */
    private fun insertMonth(deviceKey: Long, minutes: Int) {
        val db = database.openHelper.writableDatabase
        val insert = db.compileStatement(
            "INSERT INTO locations (client_id, device_id, latitude, longitude, accuracy_m, is_mock, recorded_at, received_at) " +
                "VALUES (?, ?, ?, ?, 10, 0, ?, ?)",
        )
        db.beginTransaction()
        try {
            for (m in 0 until minutes) {
                val minuteOfDay = m % 1_440
                val moving = minuteOfDay < 60
                val northM = if (moving) minuteOfDay * 50.0 else 3_000.0 + (m % 7) - 3
                val eastM = if (moving) sin(minuteOfDay / 6.0) * 300.0 else (m % 5) - 2.0
                val time = T0.plus(Duration.ofMinutes(m.toLong())).toEpochMilli()
                insert.clearBindings()
                insert.bindString(1, UUID.randomUUID().toString())
                insert.bindLong(2, deviceKey)
                insert.bindDouble(3, 19.4326 + northM / 111_195.0)
                insert.bindDouble(4, -99.1332 + deviceKey * 0.01 + eastM / 104_900.0)
                insert.bindLong(5, time)
                insert.bindLong(6, time)
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val NAME = "history-performance-test.db"
        const val MONTH_MINUTES = 30 * 24 * 60
    }
}
