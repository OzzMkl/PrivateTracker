package org.privatetracker.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.privatetracker.core.database.server.DeviceDao
import org.privatetracker.core.database.server.DeviceEntity
import org.privatetracker.core.database.server.DeviceSessionEntity
import org.privatetracker.core.database.server.LocationDao
import org.privatetracker.core.database.server.LocationEntity
import org.privatetracker.core.database.server.SessionDao
import org.privatetracker.core.database.tracker.OutboxDao
import org.privatetracker.core.database.tracker.OutboxLocationEntity

// Two files with different lifecycles: the tracker outbox drains, the server accumulates and purges.
// Neither builder allows destructive migration: a missing migration must fail, never wipe user data.

@Database(
    entities = [DeviceEntity::class, LocationEntity::class, DeviceSessionEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // 0.2: devices.approval.
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun locations(): LocationDao
    abstract fun sessions(): SessionDao

    companion object {
        const val NAME = "server.db"

        fun build(context: Context): ServerDatabase =
            Room.databaseBuilder(context, ServerDatabase::class.java, NAME).build()
    }
}

@Database(
    entities = [OutboxLocationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        const val NAME = "tracker.db"

        fun build(context: Context): TrackerDatabase =
            Room.databaseBuilder(context, TrackerDatabase::class.java, NAME).build()
    }
}
