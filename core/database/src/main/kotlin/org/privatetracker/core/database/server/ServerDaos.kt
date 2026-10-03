package org.privatetracker.core.database.server

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Devices joined with their last location; the join needs no grouping or MAX per device. */
private const val WITH_LAST_LOCATION = """
    SELECT d.*,
        l.id AS loc_id, l.client_id AS loc_client_id, l.device_id AS loc_device_id, l.session_id AS loc_session_id,
        l.latitude AS loc_latitude, l.longitude AS loc_longitude, l.accuracy_m AS loc_accuracy_m,
        l.altitude_m AS loc_altitude_m, l.speed_mps AS loc_speed_mps, l.bearing_deg AS loc_bearing_deg,
        l.provider AS loc_provider, l.battery_pct AS loc_battery_pct, l.is_mock AS loc_is_mock,
        l.recorded_at AS loc_recorded_at, l.received_at AS loc_received_at
    FROM devices d
    LEFT JOIN locations l ON l.id = d.last_location_id
"""

private const val SESSIONS = """
    SELECT s.*, d.device_uid AS device_uid
    FROM device_sessions s
    JOIN devices d ON d.id = s.device_id
"""

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE device_uid = :uid")
    suspend fun get(uid: String): DeviceEntity?

    @Query("SELECT id FROM devices WHERE device_uid = :uid")
    suspend fun internalId(uid: String): Long?

    @Query("$WITH_LAST_LOCATION WHERE d.device_uid = :uid")
    suspend fun getWithLastLocation(uid: String): DeviceWithLastLocationRow?

    @Query(WITH_LAST_LOCATION)
    suspend fun getAllWithLastLocation(): List<DeviceWithLastLocationRow>

    @Query(WITH_LAST_LOCATION)
    fun observeAllWithLastLocation(): Flow<List<DeviceWithLastLocationRow>>

    @Insert
    suspend fun insert(device: DeviceEntity): Long

    /** Updates what a device reports about itself; never touches created_at or last_location_id. */
    @Query(
        """
        UPDATE devices
        SET name = :name, platform = :platform, app_version = :appVersion, protocol_version = :protocolVersion,
            public_key = :publicKey, last_seen_at = :lastSeenAt
        WHERE device_uid = :uid
        """,
    )
    suspend fun updateDetails(
        uid: String,
        name: String,
        platform: String,
        appVersion: String?,
        protocolVersion: Int,
        publicKey: String?,
        lastSeenAt: Long?,
    ): Int

    @Query(
        """
        UPDATE devices
        SET last_location_id = (SELECT l.id FROM locations l WHERE l.device_id = devices.id AND l.client_id = :clientId)
        WHERE device_uid = :uid
        """,
    )
    suspend fun setLastLocation(uid: String, clientId: String): Int

    /** Locations and sessions go with it (ON DELETE CASCADE). */
    @Query("DELETE FROM devices WHERE device_uid = :uid")
    suspend fun delete(uid: String): Int
}

@Dao
interface LocationDao {
    /** Returns the new row id, or -1 when (device_id, client_id) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(location: LocationEntity): Long

    @Query(
        """
        DELETE FROM locations
        WHERE received_at < :cutoff
          AND id NOT IN (SELECT last_location_id FROM devices WHERE last_location_id IS NOT NULL)
        """,
    )
    suspend fun deleteReceivedBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM locations l JOIN devices d ON d.id = l.device_id WHERE d.device_uid = :deviceUid")
    suspend fun countForDevice(deviceUid: String): Int
}

@Dao
interface SessionDao {
    @Query("SELECT id FROM device_sessions WHERE session_uid = :uid")
    suspend fun internalId(uid: String): Long?

    @Query("$SESSIONS WHERE d.device_uid = :deviceUid AND s.ended_at IS NULL LIMIT 1")
    suspend fun findOpen(deviceUid: String): SessionRow?

    @Query("$SESSIONS WHERE s.ended_at IS NULL")
    suspend fun findAllOpen(): List<SessionRow>

    @Query("$SESSIONS WHERE s.ended_at IS NULL AND s.last_activity_at < :cutoff")
    suspend fun findOpenInactiveSince(cutoff: Long): List<SessionRow>

    @Query("$SESSIONS WHERE d.device_uid = :deviceUid ORDER BY s.started_at DESC LIMIT :limit")
    suspend fun findRecent(deviceUid: String, limit: Int): List<SessionRow>

    @Insert
    suspend fun insert(session: DeviceSessionEntity): Long

    @Query(
        """
        UPDATE device_sessions
        SET last_activity_at = :lastActivityAt, ended_at = :endedAt, end_reason = :endReason,
            remote_address = :remoteAddress, app_version = :appVersion, locations_received = :locationsReceived
        WHERE session_uid = :uid
        """,
    )
    suspend fun update(
        uid: String,
        lastActivityAt: Long,
        endedAt: Long?,
        endReason: String?,
        remoteAddress: String?,
        appVersion: String?,
        locationsReceived: Int,
    ): Int
}
