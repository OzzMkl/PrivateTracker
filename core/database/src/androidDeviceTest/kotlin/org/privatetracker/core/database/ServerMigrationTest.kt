package org.privatetracker.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every migration runs against the schema exported for the version it starts from. */
@RunWith(AndroidJUnit4::class)
class ServerMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ServerDatabase::class.java)

    @Test
    fun version1DevicesKeepTheirDataAndWaitForApproval() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO devices (device_uid, name, platform, app_version, protocol_version, public_key, created_at, last_seen_at) " +
                    "VALUES ('6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21', 'Pixel de Ana', 'ANDROID', '0.1.0', 1, NULL, 1000, 2000)",
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT name, last_seen_at, approval FROM devices").use { row ->
                row.moveToFirst()
                assertEquals("Pixel de Ana", row.getString(0))
                assertEquals(2000L, row.getLong(1))
                assertEquals("PENDING", row.getString(2))
            }
        }
    }

    @Test
    fun version2KeepsEverythingAndAddsEmptyGeofenceTables() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO devices (device_uid, name, platform, app_version, protocol_version, public_key, created_at, last_seen_at, approval) " +
                    "VALUES ('6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21', 'Pixel de Ana', 'ANDROID', '0.6.0', 1, 'a2V5', 1000, 2000, 'APPROVED')",
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT name, approval FROM devices").use { row ->
                row.moveToFirst()
                assertEquals("Pixel de Ana", row.getString(0))
                assertEquals("APPROVED", row.getString(1))
            }
            for (table in listOf("geofences", "geofence_devices", "geofence_states", "geofence_events")) {
                db.query("SELECT COUNT(*) FROM $table").use { row ->
                    row.moveToFirst()
                    assertEquals(table, 0, row.getInt(0))
                }
            }
        }
    }

    @Test
    fun version3KeepsEverythingAndAddsWhatPairedServersNeed() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO devices (device_uid, name, platform, app_version, protocol_version, public_key, created_at, last_seen_at, approval) " +
                    "VALUES ('6f1c2a8e-3b7d-4c1e-9a52-0d8e7f4b9c21', 'Pixel de Ana', 'ANDROID', '0.7.0', 1, 'a2V5', 1000, 2000, 'APPROVED')",
            )
            db.execSQL(
                "INSERT INTO locations (client_id, device_id, session_id, latitude, longitude, is_mock, recorded_at, received_at) " +
                    "VALUES ('b3e1f7a2-5c4d-4e8f-a1b2-000000000001', 1, NULL, 19.43, -99.13, 0, 1500, 1600)",
            )
        }

        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            // Numbered on first use, not here: until then every device counts as change 0.
            db.query("SELECT name, approval, updated_at, approval_changed_at, change_seq FROM devices").use { row ->
                row.moveToFirst()
                assertEquals("Pixel de Ana", row.getString(0))
                assertEquals("APPROVED", row.getString(1))
                assertEquals(true, row.isNull(2) && row.isNull(3))
                assertEquals(0L, row.getLong(4))
            }
            db.query("SELECT from_peer FROM locations").use { row ->
                row.moveToFirst()
                assertEquals(true, row.isNull(0))
            }
            for (table in listOf("peers", "removed_devices", "sync_counters")) {
                db.query("SELECT COUNT(*) FROM $table").use { row ->
                    row.moveToFirst()
                    assertEquals(table, 0, row.getInt(0))
                }
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
