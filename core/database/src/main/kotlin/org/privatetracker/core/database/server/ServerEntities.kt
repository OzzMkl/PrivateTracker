package org.privatetracker.core.database.server

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// server.db, as designed in section 6. Internal INTEGER keys join the tables; the public UUIDs are
// stored once in their own column, so each location row references its device with 8 bytes, not 36.
// Times are epoch milliseconds in UTC.

@Entity(
    tableName = "devices",
    indices = [Index(value = ["device_uid"], unique = true)],
)
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "device_uid") val deviceUid: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "app_version") val appVersion: String?,
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
    @ColumnInfo(name = "public_key") val publicKey: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
    /** Points at locations.id. No declared foreign key: locations already reference devices. */
    @ColumnInfo(name = "last_location_id") val lastLocationId: Long?,
)

@Entity(
    tableName = "locations",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DeviceSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        // Idempotency: a resent location collides here and is ignored. Also covers the device_id key.
        Index(value = ["device_id", "client_id"], unique = true),
        Index(value = ["device_id", "recorded_at"]),
        Index(value = ["received_at"]),
        Index(value = ["session_id"]),
    ],
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "device_id") val deviceId: Long,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
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
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(
    tableName = "device_sessions",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_uid"], unique = true),
        Index(value = ["device_id", "ended_at"]),
        Index(value = ["ended_at", "last_activity_at"]),
    ],
)
data class DeviceSessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_uid") val sessionUid: String,
    @ColumnInfo(name = "device_id") val deviceId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "last_activity_at") val lastActivityAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "end_reason") val endReason: String?,
    @ColumnInfo(name = "remote_address") val remoteAddress: String?,
    @ColumnInfo(name = "app_version") val appVersion: String?,
    @ColumnInfo(name = "locations_received", defaultValue = "0") val locationsReceived: Int,
)

/** A device and the row its last_location_id points at, if any. */
data class DeviceWithLastLocationRow(
    @Embedded val device: DeviceEntity,
    @Embedded(prefix = "loc_") val lastLocation: LocationEntity?,
)

/** A session with the public id of its device. */
data class SessionRow(
    @Embedded val session: DeviceSessionEntity,
    @ColumnInfo(name = "device_uid") val deviceUid: String,
)
