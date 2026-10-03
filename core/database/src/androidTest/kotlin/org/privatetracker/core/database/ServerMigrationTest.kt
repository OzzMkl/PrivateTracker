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

    private companion object {
        const val DB = "migration-test.db"
    }
}
