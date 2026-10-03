package org.privatetracker.app.di

import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.privatetracker.BuildConfig
import org.privatetracker.app.platform.AndroidBatteryLevelProvider
import org.privatetracker.app.platform.PlatformLocationSource
import org.privatetracker.app.server.ServerRuntime
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.port.BatteryLevelProvider
import org.privatetracker.core.domain.port.LocationSource
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.IdentityRepository
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.usecase.server.CloseAllSessions
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.PurgeExpiredLocations
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UpdateTrackerConfig
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.network.KtorServerGateway
import org.privatetracker.core.network.createProtocolHttpClient
import org.privatetracker.server.api.ServerDependencies
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope that outlives screens and services, for work such as an upload that must finish. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun clock(): Clock = Clock.System

    @Provides @Singleton fun ids(): IdGenerator = IdGenerator.Random

    @Provides @Singleton fun appInfo(): AppInfo = AppInfo(Platform.ANDROID, BuildConfig.VERSION_NAME)

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {
    @Binds abstract fun locationSource(source: PlatformLocationSource): LocationSource

    @Binds abstract fun battery(provider: AndroidBatteryLevelProvider): BatteryLevelProvider
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun httpClient(appInfo: AppInfo): HttpClient =
        createProtocolHttpClient(OkHttp.create(), "PrivateTracker-Tracker/${appInfo.version} (Android ${Build.VERSION.RELEASE})")

    @Provides @Singleton fun gateway(client: HttpClient): ServerGateway = KtorServerGateway(client)
}

/** The domain is framework-free, so its classes are wired here instead of with @Inject constructors. */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    @Provides @Singleton fun validator(clock: Clock) = LocationValidator(clock)

    @Provides @Singleton fun sessionTracker(sessions: SessionRepository, ids: IdGenerator) = SessionTracker(sessions, ids)

    @Provides fun identity(repository: IdentityRepository, ids: IdGenerator) = GetOrCreateDeviceIdentity(repository, ids)

    // Server maintenance

    @Provides
    fun closeInactiveSessions(
        sessions: SessionRepository,
        sessionTracker: SessionTracker,
        config: ServerConfigRepository,
        transactions: TransactionRunner,
        clock: Clock,
    ) = CloseInactiveSessions(sessions, sessionTracker, config, transactions, clock)

    @Provides
    fun purgeExpiredLocations(locations: LocationRepository, config: ServerConfigRepository, clock: Clock) =
        PurgeExpiredLocations(locations, config, clock)

    // Server

    @Provides
    fun registerOrUpdateDevice(
        devices: DeviceRepository,
        config: ServerConfigRepository,
        sessionTracker: SessionTracker,
        transactions: TransactionRunner,
        clock: Clock,
    ) = RegisterOrUpdateDevice(devices, config, sessionTracker, transactions, clock)

    @Provides
    fun ingestLocationBatch(
        devices: DeviceRepository,
        locations: LocationRepository,
        sessions: SessionRepository,
        config: ServerConfigRepository,
        sessionTracker: SessionTracker,
        validator: LocationValidator,
        transactions: TransactionRunner,
        clock: Clock,
    ) = IngestLocationBatch(devices, locations, sessions, config, sessionTracker, validator, transactions, clock)

    @Provides
    fun getDeviceOverviews(devices: DeviceRepository, config: ServerConfigRepository, clock: Clock) =
        GetDeviceOverviews(devices, config, clock)

    @Provides
    fun observeDeviceOverviews(devices: DeviceRepository, config: ServerConfigRepository, clock: Clock) =
        ObserveDeviceOverviews(devices, config, clock)

    @Provides
    fun getDeviceDetail(devices: DeviceRepository, sessions: SessionRepository, config: ServerConfigRepository, clock: Clock) =
        GetDeviceDetail(devices, sessions, config, clock)

    @Provides
    fun closeAllSessions(sessions: SessionRepository, sessionTracker: SessionTracker, transactions: TransactionRunner, clock: Clock) =
        CloseAllSessions(sessions, sessionTracker, transactions, clock)

    @Provides @Singleton
    fun serverDependencies(
        appInfo: AppInfo,
        clock: Clock,
        config: ServerConfigRepository,
        register: RegisterOrUpdateDevice,
        ingest: IngestLocationBatch,
        overviews: GetDeviceOverviews,
        detail: GetDeviceDetail,
        runtime: ServerRuntime,
    ) = ServerDependencies(
        serverVersion = appInfo.version,
        clock = clock,
        serverConfig = config,
        registerOrUpdateDevice = register,
        ingestLocationBatch = ingest,
        getDeviceOverviews = overviews,
        getDeviceDetail = detail,
        isShuttingDown = { runtime.shuttingDown },
    )

    // Tracker

    @Provides
    fun recordLocation(
        outbox: OutboxRepository,
        state: TrackerStateRepository,
        config: TrackerConfigRepository,
        identity: GetOrCreateDeviceIdentity,
        battery: BatteryLevelProvider,
        validator: LocationValidator,
        ids: IdGenerator,
    ) = RecordLocation(outbox, state, config, identity, battery, validator, ids)

    @Provides
    fun registerDevice(
        gateway: ServerGateway,
        config: TrackerConfigRepository,
        state: TrackerStateRepository,
        identity: GetOrCreateDeviceIdentity,
        appInfo: AppInfo,
        clock: Clock,
    ) = RegisterDevice(gateway, config, state, identity, appInfo, clock)

    @Provides
    fun uploadPendingLocations(
        outbox: OutboxRepository,
        gateway: ServerGateway,
        config: TrackerConfigRepository,
        state: TrackerStateRepository,
        register: RegisterDevice,
        identity: GetOrCreateDeviceIdentity,
        clock: Clock,
    ) = UploadPendingLocations(outbox, gateway, config, state, register, identity, clock)

    @Provides fun updateTrackerConfig(config: TrackerConfigRepository) = UpdateTrackerConfig(config)

    @Provides fun testServerConnection(gateway: ServerGateway, clock: Clock) = TestServerConnection(gateway, clock)
}
