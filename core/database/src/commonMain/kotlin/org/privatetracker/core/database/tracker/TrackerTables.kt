package org.privatetracker.core.database.tracker

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** tracker.db: locations captured but not yet acknowledged. The autoincrement id is the send order. */
@Entity(
    tableName = "outbox_locations",
    indices = [Index(value = ["client_id"], unique = true)],
)
data class OutboxLocationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "device_uid") val deviceUid: String,
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Float?,
    @ColumnInfo(name = "provider") val provider: String?,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int?,
    @ColumnInfo(name = "is_mock", defaultValue = "0") val isMock: Boolean,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@Dao
abstract class OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(location: OutboxLocationEntity): Long

    @Query("DELETE FROM outbox_locations WHERE id IN (SELECT id FROM outbox_locations ORDER BY id LIMIT :count)")
    protected abstract suspend fun deleteOldest(count: Int): Int

    /** Appends and trims to [maxSize] atomically. Returns how many of the oldest were dropped. */
    @Transaction
    open suspend fun enqueue(location: OutboxLocationEntity, maxSize: Int): Int {
        insert(location)
        val excess = count() - maxSize
        return if (excess > 0) deleteOldest(excess) else 0
    }

    @Query("SELECT * FROM outbox_locations ORDER BY id LIMIT :limit")
    abstract suspend fun peek(limit: Int): List<OutboxLocationEntity>

    @Query("DELETE FROM outbox_locations WHERE client_id IN (:clientIds)")
    abstract suspend fun delete(clientIds: List<String>): Int

    @Query(
        """
        UPDATE outbox_locations
        SET attempts = attempts + 1, last_attempt_at = :at, last_error = :errorCode
        WHERE client_id IN (:clientIds)
        """,
    )
    abstract suspend fun markAttempt(clientIds: List<String>, at: Long, errorCode: String): Int

    @Query("SELECT COUNT(*) FROM outbox_locations")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM outbox_locations")
    abstract fun observeCount(): Flow<Int>
}
