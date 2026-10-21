package org.privatetracker.core.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import org.privatetracker.core.database.server.DeviceDao
import org.privatetracker.core.database.server.DeviceEntity
import org.privatetracker.core.database.server.DeviceSessionEntity
import org.privatetracker.core.database.server.GeofenceDao
import org.privatetracker.core.database.server.GeofenceDeviceEntity
import org.privatetracker.core.database.server.GeofenceEntity
import org.privatetracker.core.database.server.GeofenceEventEntity
import org.privatetracker.core.database.server.GeofenceStateEntity
import org.privatetracker.core.database.server.LocationDao
import org.privatetracker.core.database.server.LocationEntity
import org.privatetracker.core.database.server.PeerEntity
import org.privatetracker.core.database.server.RemovedDeviceEntity
import org.privatetracker.core.database.server.SessionDao
import org.privatetracker.core.database.server.SyncCounterEntity
import org.privatetracker.core.database.server.SyncDao
import org.privatetracker.core.database.tracker.OutboxDao
import org.privatetracker.core.database.tracker.OutboxLocationEntity

// Two files with different lifecycles: the tracker outbox drains, the server accumulates and purges.
// Neither builder allows destructive migration: a missing migration must fail, never wipe user data.
// From 1.0 the same tables also run on the JVM (the Linux server), so a server.db can move from a
// phone to a Linux host. Builders live per platform: databaseBuilders.android.kt and .jvm.kt.

@Database(
    entities = [
        DeviceEntity::class,
        LocationEntity::class,
        DeviceSessionEntity::class,
        GeofenceEntity::class,
        GeofenceDeviceEntity::class,
        GeofenceStateEntity::class,
        GeofenceEventEntity::class,
        PeerEntity::class,
        RemovedDeviceEntity::class,
        SyncCounterEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // 0.2: devices.approval.
        AutoMigration(from = 1, to = 2),
        // 0.7: geofences, their devices, detection states and events; new tables only.
        AutoMigration(from = 2, to = 3),
        // 1.0: paired servers, removed devices, change numbers; new tables and nullable or defaulted columns.
        AutoMigration(from = 3, to = 4),
    ],
)
@ConstructedBy(ServerDatabaseConstructor::class)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun locations(): LocationDao
    abstract fun sessions(): SessionDao
    abstract fun geofences(): GeofenceDao
    abstract fun sync(): SyncDao

    companion object {
        const val NAME = "server.db"
    }
}

/** Room generates the actual for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object ServerDatabaseConstructor : RoomDatabaseConstructor<ServerDatabase> {
    override fun initialize(): ServerDatabase
}

@Database(
    entities = [OutboxLocationEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(TrackerDatabaseConstructor::class)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        const val NAME = "tracker.db"
    }
}

@Suppress("KotlinNoActualForExpect")
expect object TrackerDatabaseConstructor : RoomDatabaseConstructor<TrackerDatabase> {
    override fun initialize(): TrackerDatabase
}
