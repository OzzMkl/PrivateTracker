package org.privatetracker.app.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.data.repository.DataStoreAppModeRepository
import org.privatetracker.core.data.repository.DataStoreEncryptionKeyRepository
import org.privatetracker.core.data.repository.DataStoreIdentityRepository
import org.privatetracker.core.data.repository.DataStoreServerConfigRepository
import org.privatetracker.core.data.repository.DataStoreTrackerConfigRepository
import org.privatetracker.core.data.repository.DataStoreTrackerStateRepository
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomGeofenceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.data.repository.RoomOutboxRepository
import org.privatetracker.core.data.repository.RoomSessionRepository
import org.privatetracker.core.data.repository.RoomTransactionRunner
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.build
import org.privatetracker.core.database.TrackerDatabase
import org.privatetracker.core.database.server.DeviceDao
import org.privatetracker.core.database.server.GeofenceDao
import org.privatetracker.core.database.server.LocationDao
import org.privatetracker.core.database.server.SessionDao
import org.privatetracker.core.database.tracker.OutboxDao
import org.privatetracker.core.datastore.AppModeData
import org.privatetracker.core.datastore.IdentityData
import org.privatetracker.core.datastore.ServerConfigData
import org.privatetracker.core.datastore.ServerEncryptionKeysData
import org.privatetracker.core.datastore.HttpsAddressesMigration
import org.privatetracker.core.datastore.TrackerConfigData
import org.privatetracker.core.datastore.TrackerStateData
import org.privatetracker.core.datastore.createJsonDataStore
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.EncryptionKeyRepository
import org.privatetracker.core.domain.repository.GeofenceRepository
import org.privatetracker.core.domain.repository.IdentityRepository
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import javax.inject.Singleton

/** Databases and stores. Each must be a singleton: two DataStores on one file fail at runtime. */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun serverDatabase(@ApplicationContext context: Context): ServerDatabase = ServerDatabase.build(context)

    @Provides @Singleton
    fun trackerDatabase(@ApplicationContext context: Context): TrackerDatabase = TrackerDatabase.build(context)

    @Provides fun deviceDao(database: ServerDatabase): DeviceDao = database.devices()
    @Provides fun locationDao(database: ServerDatabase): LocationDao = database.locations()
    @Provides fun sessionDao(database: ServerDatabase): SessionDao = database.sessions()
    @Provides fun geofenceDao(database: ServerDatabase): GeofenceDao = database.geofences()
    @Provides fun outboxDao(database: TrackerDatabase): OutboxDao = database.outbox()

    @Provides @Singleton
    fun trackerConfigStore(@ApplicationContext context: Context): DataStore<TrackerConfigData> =
        createJsonDataStore(
            context,
            "tracker_config",
            TrackerConfigData.serializer(),
            TrackerConfigData(deviceName = Build.MODEL),
            migrations = listOf(HttpsAddressesMigration),
        )

    @Provides @Singleton
    fun serverConfigStore(@ApplicationContext context: Context): DataStore<ServerConfigData> =
        createJsonDataStore(context, "server_config", ServerConfigData.serializer(), ServerConfigData())

    @Provides @Singleton
    fun identityStore(@ApplicationContext context: Context): DataStore<IdentityData> =
        createJsonDataStore(context, "identity", IdentityData.serializer(), IdentityData())

    @Provides @Singleton
    fun appModeStore(@ApplicationContext context: Context): DataStore<AppModeData> =
        createJsonDataStore(context, "app_mode", AppModeData.serializer(), AppModeData())

    @Provides @Singleton
    fun trackerStateStore(@ApplicationContext context: Context): DataStore<TrackerStateData> =
        createJsonDataStore(context, "tracker_state", TrackerStateData.serializer(), TrackerStateData())

    /** Excluded from backups and device transfers, like identity.json: its keys only open on this phone. */
    @Provides @Singleton
    fun serverEncryptionKeysStore(@ApplicationContext context: Context): DataStore<ServerEncryptionKeysData> =
        createJsonDataStore(context, "server_encryption_keys", ServerEncryptionKeysData.serializer(), ServerEncryptionKeysData())
}

/**
 * core:data is framework-free since 1.0, when its Room repositories also serve the Linux server, so its
 * classes are wired here with @Provides, like the domain's.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides fun transactions(database: ServerDatabase): TransactionRunner = RoomTransactionRunner(database)
    @Provides fun devices(dao: DeviceDao): DeviceRepository = RoomDeviceRepository(dao)

    @Provides
    fun locations(locations: LocationDao, devices: DeviceDao, sessions: SessionDao): LocationRepository =
        RoomLocationRepository(locations, devices, sessions)

    @Provides fun sessions(sessions: SessionDao, devices: DeviceDao): SessionRepository = RoomSessionRepository(sessions, devices)
    @Provides fun outbox(dao: OutboxDao): OutboxRepository = RoomOutboxRepository(dao)

    @Provides
    fun geofences(database: ServerDatabase, dao: GeofenceDao, devices: DeviceDao): GeofenceRepository =
        RoomGeofenceRepository(database, dao, devices)

    @Provides
    fun trackerConfig(store: DataStore<TrackerConfigData>): TrackerConfigRepository = DataStoreTrackerConfigRepository(store)

    @Provides
    fun serverConfig(store: DataStore<ServerConfigData>): ServerConfigRepository = DataStoreServerConfigRepository(store)

    @Provides fun identity(store: DataStore<IdentityData>): IdentityRepository = DataStoreIdentityRepository(store)

    @Provides
    fun trackerState(store: DataStore<TrackerStateData>): TrackerStateRepository = DataStoreTrackerStateRepository(store)

    @Provides fun appMode(store: DataStore<AppModeData>): AppModeRepository = DataStoreAppModeRepository(store)

    @Provides
    fun encryptionKeys(store: DataStore<ServerEncryptionKeysData>): EncryptionKeyRepository = DataStoreEncryptionKeyRepository(store)
}
