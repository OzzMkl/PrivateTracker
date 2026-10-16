package org.privatetracker.core.database.server

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Geofences, from server.db version 3 (0.7). Deleting a geofence or a device cascades to its device
// choices, its detection states and its events.

@Entity(tableName = "geofences", indices = [Index(value = ["geofence_uid"], unique = true)])
data class GeofenceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "geofence_uid") val geofenceUid: String,
    @ColumnInfo(name = "name") val name: String,
    /** CIRCLE or POLYGON. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "center_latitude") val centerLatitude: Double?,
    @ColumnInfo(name = "center_longitude") val centerLongitude: Double?,
    @ColumnInfo(name = "radius_m") val radiusM: Double?,
    /** Polygon vertices as `lat,lon;lat,lon;…`. */
    @ColumnInfo(name = "vertices") val vertices: String?,
    /** True when it watches every device; otherwise geofence_devices lists them. */
    @ColumnInfo(name = "all_devices") val allDevices: Boolean,
    @ColumnInfo(name = "notify_enter") val notifyEnter: Boolean,
    @ColumnInfo(name = "notify_exit") val notifyExit: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "geofence_devices",
    primaryKeys = ["geofence_id", "device_id"],
    foreignKeys = [
        ForeignKey(entity = GeofenceEntity::class, parentColumns = ["id"], childColumns = ["geofence_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DeviceEntity::class, parentColumns = ["id"], childColumns = ["device_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["device_id"])],
)
data class GeofenceDeviceEntity(
    @ColumnInfo(name = "geofence_id") val geofenceId: Long,
    @ColumnInfo(name = "device_id") val deviceId: Long,
)

@Entity(
    tableName = "geofence_states",
    primaryKeys = ["geofence_id", "device_id"],
    foreignKeys = [
        ForeignKey(entity = GeofenceEntity::class, parentColumns = ["id"], childColumns = ["geofence_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DeviceEntity::class, parentColumns = ["id"], childColumns = ["device_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["device_id"])],
)
data class GeofenceStateEntity(
    @ColumnInfo(name = "geofence_id") val geofenceId: Long,
    @ColumnInfo(name = "device_id") val deviceId: Long,
    @ColumnInfo(name = "inside") val inside: Boolean?,
    @ColumnInfo(name = "candidate_inside") val candidateInside: Boolean?,
    @ColumnInfo(name = "candidate_count") val candidateCount: Int,
    @ColumnInfo(name = "candidate_since") val candidateSince: Long?,
    @ColumnInfo(name = "last_recorded_at") val lastRecordedAt: Long,
)

@Entity(
    tableName = "geofence_events",
    foreignKeys = [
        ForeignKey(entity = GeofenceEntity::class, parentColumns = ["id"], childColumns = ["geofence_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DeviceEntity::class, parentColumns = ["id"], childColumns = ["device_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["geofence_id"]),
        Index(value = ["device_id"]),
        Index(value = ["occurred_at"]),
        Index(value = ["detected_at"]),
    ],
)
data class GeofenceEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "geofence_id") val geofenceId: Long,
    @ColumnInfo(name = "device_id") val deviceId: Long,
    /** ENTER or EXIT. */
    @ColumnInfo(name = "transition") val transition: String,
    /** Device time of the first position on the new side. */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    /** Server time of the confirmation; retention counts from it. */
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "alerted") val alerted: Boolean,
)

/** A geofence and one device it names; [deviceUid] null for one that watches every device. */
data class GeofenceDeviceRow(
    @Embedded val geofence: GeofenceEntity,
    @ColumnInfo(name = "device_uid") val deviceUid: String?,
)

data class GeofenceStateRow(
    @Embedded val state: GeofenceStateEntity,
    @ColumnInfo(name = "geofence_uid") val geofenceUid: String,
    @ColumnInfo(name = "device_uid") val deviceUid: String,
)

/** An event with the public ids and the names to show. */
data class GeofenceEventRow(
    @Embedded val event: GeofenceEventEntity,
    @ColumnInfo(name = "geofence_uid") val geofenceUid: String,
    @ColumnInfo(name = "geofence_name") val geofenceName: String,
    @ColumnInfo(name = "device_uid") val deviceUid: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
)

private const val WITH_DEVICES = """
    SELECT g.*, d.device_uid FROM geofences g
    LEFT JOIN geofence_devices gd ON gd.geofence_id = g.id
    LEFT JOIN devices d ON d.id = gd.device_id
"""

@Dao
interface GeofenceDao {
    @Query("$WITH_DEVICES ORDER BY g.created_at")
    fun observeAll(): Flow<List<GeofenceDeviceRow>>

    @Query("$WITH_DEVICES ORDER BY g.created_at")
    suspend fun all(): List<GeofenceDeviceRow>

    @Query("$WITH_DEVICES WHERE g.geofence_uid = :uid")
    suspend fun get(uid: String): List<GeofenceDeviceRow>

    @Query("SELECT id FROM geofences WHERE geofence_uid = :uid")
    suspend fun internalId(uid: String): Long?

    @Insert
    suspend fun insert(geofence: GeofenceEntity): Long

    @Query(
        """
        UPDATE geofences SET name = :name, kind = :kind, center_latitude = :centerLatitude,
            center_longitude = :centerLongitude, radius_m = :radiusM, vertices = :vertices,
            all_devices = :allDevices, notify_enter = :notifyEnter, notify_exit = :notifyExit
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: Long,
        name: String,
        kind: String,
        centerLatitude: Double?,
        centerLongitude: Double?,
        radiusM: Double?,
        vertices: String?,
        allDevices: Boolean,
        notifyEnter: Boolean,
        notifyExit: Boolean,
    )

    @Query("DELETE FROM geofence_devices WHERE geofence_id = :geofenceId")
    suspend fun clearDevices(geofenceId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevices(devices: List<GeofenceDeviceEntity>)

    @Query("DELETE FROM geofence_states WHERE geofence_id = :geofenceId")
    suspend fun clearStates(geofenceId: Long)

    /** For a geofence that names its devices: forgets the others. */
    @Query(
        """
        DELETE FROM geofence_states WHERE geofence_id = :geofenceId
            AND device_id NOT IN (SELECT device_id FROM geofence_devices WHERE geofence_id = :geofenceId)
        """,
    )
    suspend fun clearUnwatchedStates(geofenceId: Long)

    @Query("DELETE FROM geofences WHERE geofence_uid = :uid")
    suspend fun delete(uid: String): Int

    @Query(
        """
        SELECT s.*, g.geofence_uid, d.device_uid FROM geofence_states s
        JOIN geofences g ON g.id = s.geofence_id JOIN devices d ON d.id = s.device_id
        WHERE g.geofence_uid = :geofenceUid AND d.device_uid = :deviceUid
        """,
    )
    suspend fun state(geofenceUid: String, deviceUid: String): GeofenceStateRow?

    @Upsert
    suspend fun saveState(state: GeofenceStateEntity)

    @Insert
    suspend fun insertEvent(event: GeofenceEventEntity)

    @Query(
        """
        SELECT e.*, g.geofence_uid, g.name AS geofence_name, d.device_uid, d.name AS device_name
        FROM geofence_events e JOIN geofences g ON g.id = e.geofence_id JOIN devices d ON d.id = e.device_id
        ORDER BY e.occurred_at DESC LIMIT :limit
        """,
    )
    fun observeEvents(limit: Int): Flow<List<GeofenceEventRow>>

    @Query("DELETE FROM geofence_events WHERE detected_at < :cutoff")
    suspend fun deleteEventsBefore(cutoff: Long): Int
}
