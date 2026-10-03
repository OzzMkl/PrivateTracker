package org.privatetracker.core.data.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.data.repository.DataStoreAppModeRepository
import org.privatetracker.core.data.repository.DataStoreIdentityRepository
import org.privatetracker.core.data.repository.DataStoreServerConfigRepository
import org.privatetracker.core.data.repository.DataStoreTrackerConfigRepository
import org.privatetracker.core.data.repository.DataStoreTrackerStateRepository
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.data.repository.RoomOutboxRepository
import org.privatetracker.core.data.repository.RoomSessionRepository
import org.privatetracker.core.data.repository.RoomTransactionRunner
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.TrackerDatabase
import org.privatetracker.core.database.server.DeviceDao
import org.privatetracker.core.database.server.LocationDao
import org.privatetracker.core.database.server.SessionDao
import org.privatetracker.core.database.tracker.OutboxDao
import org.privatetracker.core.datastore.AppModeData
import org.privatetracker.core.datastore.IdentityData
import org.privatetracker.core.datastore.ServerConfigData
import org.privatetracker.core.datastore.TrackerConfigData
import org.privatetracker.core.datastore.TrackerStateData
import org.privatetracker.core.datastore.createJsonDataStore
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.DeviceRepository
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
    @Provides fun outboxDao(database: TrackerDatabase): OutboxDao = database.outbox()

    @Provides @Singleton
    fun trackerConfigStore(@ApplicationContext context: Context): DataStore<TrackerConfigData> =
        createJsonDataStore(context, "tracker_config", TrackerConfigData.serializer(), TrackerConfigData(deviceName = Build.MODEL))

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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun transactions(runner: RoomTransactionRunner): TransactionRunner
    @Binds abstract fun devices(repository: RoomDeviceRepository): DeviceRepository
    @Binds abstract fun locations(repository: RoomLocationRepository): LocationRepository
    @Binds abstract fun sessions(repository: RoomSessionRepository): SessionRepository
    @Binds abstract fun outbox(repository: RoomOutboxRepository): OutboxRepository
    @Binds abstract fun trackerConfig(repository: DataStoreTrackerConfigRepository): TrackerConfigRepository
    @Binds abstract fun serverConfig(repository: DataStoreServerConfigRepository): ServerConfigRepository
    @Binds abstract fun identity(repository: DataStoreIdentityRepository): IdentityRepository
    @Binds abstract fun trackerState(repository: DataStoreTrackerStateRepository): TrackerStateRepository
    @Binds abstract fun appMode(repository: DataStoreAppModeRepository): AppModeRepository
}
