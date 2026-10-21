package org.privatetracker.core.database.server

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    /** Updates what a device reports about itself and its approval; never touches last_location_id. */
    @Query(
        """
        UPDATE devices
        SET name = :name, platform = :platform, app_version = :appVersion, protocol_version = :protocolVersion,
            public_key = :publicKey, created_at = :createdAt, last_seen_at = :lastSeenAt, approval = :approval,
            updated_at = :updatedAt, approval_changed_at = :approvalChangedAt, change_seq = :changeSeq
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
        createdAt: Long,
        lastSeenAt: Long?,
        approval: String,
        updatedAt: Long?,
        approvalChangedAt: Long?,
        changeSeq: Long,
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

    /** A range scan of the (device_id, recorded_at) index, already in order. */
    @Query(
        """
        SELECT l.latitude, l.longitude, l.recorded_at FROM locations l
        WHERE l.device_id = (SELECT id FROM devices WHERE device_uid = :deviceUid)
          AND l.recorded_at >= :from AND l.recorded_at < :to
        ORDER BY l.recorded_at
        """,
    )
    suspend fun findTrackPoints(deviceUid: String, from: Long, to: Long): List<TrackPointRow>

    @Query(
        """
        SELECT l.* FROM locations l
        WHERE l.device_id = (SELECT id FROM devices WHERE device_uid = :deviceUid)
          AND l.recorded_at >= :from AND l.recorded_at < :to
        ORDER BY l.recorded_at
        """,
    )
    suspend fun findRecorded(deviceUid: String, from: Long, to: Long): List<LocationEntity>
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

/** A location with the public id of its device, for a paired server. */
data class SyncLocationRow(
    @Embedded val location: LocationEntity,
    @ColumnInfo(name = "device_uid") val deviceUid: String,
)

/** 1.0: what exchanges between paired servers read and write, apart from the device and location tables' own DAOs. */
@Dao
interface SyncDao {
    /** Devices from before 1.0 all have 0: each takes its row id, so they page like any change. */
    @Query("UPDATE devices SET change_seq = id")
    suspend fun numberDevicesById()

    /** The counter goes on from the highest number [numberDevicesById] gave. */
    @Query("INSERT INTO sync_counters (name, value) VALUES (:name, (SELECT COALESCE(MAX(id), 0) FROM devices))")
    suspend fun startCounterAfterDevices(name: String)

    /** The highest location id ever given, deleted rows included; null before the first location. */
    @Query("SELECT seq FROM sqlite_sequence WHERE name = 'locations'")
    suspend fun locationSequence(): Long?

    @Query("UPDATE sync_counters SET value = value + 1 WHERE name = :name")
    suspend fun incrementCounter(name: String)

    @Query("SELECT value FROM sync_counters WHERE name = :name")
    suspend fun counter(name: String): Long?

    @Query("SELECT * FROM devices WHERE change_seq > :after ORDER BY change_seq LIMIT :limit")
    suspend fun devicesChangedAfter(after: Long, limit: Int): List<DeviceEntity>

    @Query("SELECT * FROM removed_devices WHERE change_seq > :after ORDER BY change_seq LIMIT :limit")
    suspend fun removalsAfter(after: Long, limit: Int): List<RemovedDeviceEntity>

    @Query("SELECT * FROM removed_devices WHERE device_uid = :uid")
    suspend fun removal(uid: String): RemovedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRemoval(removal: RemovedDeviceEntity)

    /** A range scan of the primary key; the rows from [peer] are skipped, never sent back where they came from. */
    @Query(
        """
        SELECT l.*, d.device_uid AS device_uid FROM locations l
        JOIN devices d ON d.id = l.device_id
        WHERE l.id > :after AND (l.from_peer IS NULL OR l.from_peer != :peer)
        ORDER BY l.id
        LIMIT :limit
        """,
    )
    suspend fun locationsAfter(after: Long, peer: Long, limit: Int): List<SyncLocationRow>

    @Query("SELECT COALESCE(MAX(id), 0) FROM locations")
    suspend fun lastLocationId(): Long

    @Query("SELECT * FROM peers ORDER BY paired_at, id")
    fun observePeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers ORDER BY paired_at, id")
    suspend fun peers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE public_key = :publicKey")
    suspend fun peer(publicKey: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE label = :label")
    suspend fun peerByLabel(label: String): PeerEntity?

    @Insert
    suspend fun insertPeer(peer: PeerEntity): Long

    @Update
    suspend fun updatePeer(peer: PeerEntity): Int

    @Query("DELETE FROM peers WHERE public_key = :publicKey")
    suspend fun deletePeer(publicKey: String): Int
}
