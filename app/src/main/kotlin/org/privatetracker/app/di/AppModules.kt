package org.privatetracker.app.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.privatetracker.BuildConfig
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.port.BatteryLevelProvider
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.EncryptionKeyVault
import org.privatetracker.core.domain.port.NetworkInfoProvider
import org.privatetracker.core.domain.port.NonceRegistry
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.port.ServerDiscovery
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.port.TrackingController
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.EncryptionKeyRepository
import org.privatetracker.core.domain.repository.IdentityRepository
import org.privatetracker.core.domain.repository.LocationRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SessionRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.repository.TrackerStateRepository
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.usecase.common.CheckPermissions
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.usecase.common.ObserveAppMode
import org.privatetracker.core.domain.usecase.common.SetAppMode
import org.privatetracker.core.domain.usecase.common.TrustOwnServer
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.CloseAllSessions
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.ExportDeviceHistory
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceTrack
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.ObserveDeviceDetail
import org.privatetracker.core.domain.usecase.server.ObserveEncryptionKey
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.ObservePairedDevice
import org.privatetracker.core.domain.usecase.server.ObserveServerStatus
import org.privatetracker.core.domain.usecase.server.PurgeExpiredLocations
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.usecase.server.RenameDevice
import org.privatetracker.core.domain.usecase.server.RestoreServer
import org.privatetracker.core.domain.usecase.server.RotateEncryptionKey
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval
import org.privatetracker.core.domain.usecase.server.StartServer
import org.privatetracker.core.domain.usecase.server.StopServer
import org.privatetracker.core.domain.usecase.server.UpdateServerConfig
import org.privatetracker.core.domain.usecase.server.WithdrawPairingInvite
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.usecase.tracker.FailOverServerAddress
import org.privatetracker.core.domain.usecase.tracker.GetCurrentServer
import org.privatetracker.core.domain.usecase.tracker.GetDeviceKeyFingerprint
import org.privatetracker.core.domain.usecase.tracker.GetServerEncryptionKey
import org.privatetracker.core.domain.usecase.tracker.ObserveTrackingStatus
import org.privatetracker.core.domain.usecase.tracker.PairWithServer
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.RestoreTracking
import org.privatetracker.core.domain.usecase.tracker.StartTracking
import org.privatetracker.core.domain.usecase.tracker.StopTracking
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UpdateTrackerConfig
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.usecase.tracker.VerifyServerIdentity
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.network.KtorServerGateway
import org.privatetracker.core.network.OkHttpPinnedClients
import org.privatetracker.core.network.PinnedClients
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun clock(): Clock = Clock.System

    @Provides @Singleton fun ids(): IdGenerator = IdGenerator.Random

    @Provides @Singleton fun appInfo(): AppInfo = AppInfo(Platform.ANDROID, BuildConfig.VERSION_NAME)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** One TLS client per server pin; each trusts only the server its pin names. */
    @Provides @Singleton
    fun pinnedClients(appInfo: AppInfo): PinnedClients =
        OkHttpPinnedClients("PrivateTracker-Tracker/${appInfo.version} (Android ${Build.VERSION.RELEASE})")

    @Provides @Singleton
    fun gateway(clients: PinnedClients, keys: DeviceKeys, clock: Clock): ServerGateway = KtorServerGateway(clients, keys, clock)
}

/**
 * The domain is framework-free, so its classes are wired here instead of with @Inject constructors.
 * The ports they need (controllers, schedulers, platform readers) are bound in the feature and core modules.
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    @Provides @Singleton fun validator(clock: Clock) = LocationValidator(clock)

    @Provides @Singleton fun sessionTracker(sessions: SessionRepository, ids: IdGenerator) = SessionTracker(sessions, ids)

    // Common

    @Provides fun identity(repository: IdentityRepository, ids: IdGenerator) = GetOrCreateDeviceIdentity(repository, ids)

    @Provides fun observeAppMode(appModes: AppModeRepository) = ObserveAppMode(appModes)

    @Provides
    fun setAppMode(
        appModes: AppModeRepository,
        trackerConfig: TrackerConfigRepository,
        serverConfig: ServerConfigRepository,
        trustOwnServer: TrustOwnServer,
        stopTracking: StopTracking,
        serverController: ServerController,
    ) = SetAppMode(appModes, trackerConfig, serverConfig, trustOwnServer, stopTracking, serverController)

    @Provides
    fun trustOwnServer(trackerConfig: TrackerConfigRepository, serverConfig: ServerConfigRepository, serverKeys: ServerKeys) =
        TrustOwnServer(trackerConfig, serverConfig, serverKeys)

    @Provides fun checkPermissions(checker: PermissionChecker) = CheckPermissions(checker)

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
    fun closeAllSessions(sessions: SessionRepository, sessionTracker: SessionTracker, transactions: TransactionRunner, clock: Clock) =
        CloseAllSessions(sessions, sessionTracker, transactions, clock)

    @Provides
    fun purgeExpiredLocations(locations: LocationRepository, config: ServerConfigRepository, clock: Clock) =
        PurgeExpiredLocations(locations, config, clock)

    // Server API

    /** Singleton: the nonces it remembers are what stops a replay. */
    @Provides @Singleton
    fun verifyRequestSignature(verifier: SignatureVerifier, nonces: NonceRegistry, clock: Clock) =
        VerifyRequestSignature(verifier, nonces, clock)

    @Provides
    fun registerOrUpdateDevice(
        devices: DeviceRepository,
        config: ServerConfigRepository,
        sessionTracker: SessionTracker,
        verifier: SignatureVerifier,
        verifySignature: VerifyRequestSignature,
        tickets: PairingTicketStore,
        transactions: TransactionRunner,
        clock: Clock,
    ) = RegisterOrUpdateDevice(devices, config, sessionTracker, verifier, verifySignature, tickets, transactions, clock)

    @Provides
    fun authenticateDevice(devices: DeviceRepository, verifySignature: VerifyRequestSignature) =
        AuthenticateDevice(devices, verifySignature)

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
    fun getDeviceDetail(devices: DeviceRepository, sessions: SessionRepository, config: ServerConfigRepository, clock: Clock) =
        GetDeviceDetail(devices, sessions, config, clock)

    // Server screens

    @Provides
    fun observeDeviceOverviews(devices: DeviceRepository, config: ServerConfigRepository, clock: Clock) =
        ObserveDeviceOverviews(devices, config, clock)

    @Provides
    fun observeDeviceDetail(devices: DeviceRepository, sessions: SessionRepository, config: ServerConfigRepository, clock: Clock) =
        ObserveDeviceDetail(devices, sessions, config, clock)

    @Provides fun renameDevice(devices: DeviceRepository, transactions: TransactionRunner) = RenameDevice(devices, transactions)

    @Provides fun getDeviceTrack(devices: DeviceRepository, locations: LocationRepository) = GetDeviceTrack(devices, locations)

    @Provides
    fun exportDeviceHistory(devices: DeviceRepository, locations: LocationRepository, clock: Clock) =
        ExportDeviceHistory(devices, locations, clock)

    @Provides fun removeDevice(devices: DeviceRepository) = RemoveDevice(devices)

    @Provides
    fun setDeviceApproval(devices: DeviceRepository, transactions: TransactionRunner) = SetDeviceApproval(devices, transactions)

    @Provides
    fun createPairingInvite(tickets: PairingTicketStore, serverKeys: ServerKeys, config: ServerConfigRepository, clock: Clock) =
        CreatePairingInvite(tickets, serverKeys, config, clock)

    @Provides fun observePairedDevice(tickets: PairingTicketStore, devices: DeviceRepository) = ObservePairedDevice(tickets, devices)

    @Provides fun getServerKeyFingerprint(serverKeys: ServerKeys) = GetServerKeyFingerprint(serverKeys)

    /** Singleton: it serializes rotations and keeps opened keys in memory. */
    @Provides @Singleton
    fun encryptionKeyRing(keys: EncryptionKeyRepository, vault: EncryptionKeyVault, serverKeys: ServerKeys, clock: Clock) =
        EncryptionKeyRing(keys, vault, serverKeys, clock)

    @Provides fun observeEncryptionKey(ring: EncryptionKeyRing) = ObserveEncryptionKey(ring)

    @Provides fun rotateEncryptionKey(ring: EncryptionKeyRing) = RotateEncryptionKey(ring)

    @Provides fun withdrawPairingInvite(tickets: PairingTicketStore) = WithdrawPairingInvite(tickets)

    @Provides
    fun updateServerConfig(config: ServerConfigRepository, trackerConfig: TrackerConfigRepository) =
        UpdateServerConfig(config, trackerConfig)

    @Provides fun startServer(checker: PermissionChecker, controller: ServerController) = StartServer(checker, controller)

    @Provides fun stopServer(controller: ServerController) = StopServer(controller)

    @Provides
    fun restoreServer(appModes: AppModeRepository, config: ServerConfigRepository, startServer: StartServer) =
        RestoreServer(appModes, config, startServer)

    @Provides
    fun observeServerStatus(controller: ServerController, config: ServerConfigRepository, network: NetworkInfoProvider) =
        ObserveServerStatus(controller, config, network)

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
        keys: DeviceKeys,
        verifyServer: VerifyServerIdentity,
        encryptionKeys: GetServerEncryptionKey,
        appInfo: AppInfo,
        clock: Clock,
    ) = RegisterDevice(gateway, config, state, identity, keys, verifyServer, encryptionKeys, appInfo, clock)

    @Provides fun verifyServerIdentity(gateway: ServerGateway, verifier: SignatureVerifier) = VerifyServerIdentity(gateway, verifier)

    @Provides
    fun getServerEncryptionKey(state: TrackerStateRepository, verifyServer: VerifyServerIdentity, clock: Clock) =
        GetServerEncryptionKey(state, verifyServer, clock)

    @Provides
    fun pairWithServer(verifyServer: VerifyServerIdentity, config: TrackerConfigRepository, register: RegisterDevice, clock: Clock) =
        PairWithServer(verifyServer, config, register, clock)

    @Provides fun getCurrentServer(config: TrackerConfigRepository) = GetCurrentServer(config)

    /** Singleton: it remembers when it last scanned the network, so a server that is off is not scanned for at every upload. */
    @Provides @Singleton
    fun failOverServerAddress(config: TrackerConfigRepository, verifyServer: VerifyServerIdentity, discovery: ServerDiscovery, clock: Clock) =
        FailOverServerAddress(config, verifyServer, discovery, clock)

    @Provides
    fun getDeviceKeyFingerprint(identity: GetOrCreateDeviceIdentity, keys: DeviceKeys) = GetDeviceKeyFingerprint(identity, keys)

    @Provides
    fun uploadPendingLocations(
        outbox: OutboxRepository,
        gateway: ServerGateway,
        config: TrackerConfigRepository,
        state: TrackerStateRepository,
        register: RegisterDevice,
        encryptionKeys: GetServerEncryptionKey,
        identity: GetOrCreateDeviceIdentity,
        clock: Clock,
    ) = UploadPendingLocations(outbox, gateway, config, state, register, encryptionKeys, identity, clock)

    @Provides fun updateTrackerConfig(config: TrackerConfigRepository) = UpdateTrackerConfig(config)

    @Provides fun testServerConnection(gateway: ServerGateway, clock: Clock) = TestServerConnection(gateway, clock)

    @Provides
    fun startTracking(config: TrackerConfigRepository, checker: PermissionChecker, controller: TrackingController) =
        StartTracking(config, checker, controller)

    @Provides
    fun stopTracking(config: TrackerConfigRepository, controller: TrackingController, scheduler: UploadScheduler) =
        StopTracking(config, controller, scheduler)

    @Provides
    fun restoreTracking(
        appModes: AppModeRepository,
        config: TrackerConfigRepository,
        checker: PermissionChecker,
        controller: TrackingController,
        trustOwnServer: TrustOwnServer,
    ) = RestoreTracking(appModes, config, checker, controller, trustOwnServer)

    @Provides
    fun observeTrackingStatus(config: TrackerConfigRepository, controller: TrackingController, outbox: OutboxRepository) =
        ObserveTrackingStatus(config, controller, outbox)
}
