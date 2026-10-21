package org.privatetracker.feature.server.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.core.domain.port.NetworkInfoProvider
import org.privatetracker.core.domain.port.NonceRegistry
import org.privatetracker.core.domain.port.PeerGateway
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.PeerRepository
import org.privatetracker.core.domain.repository.PeerTicketStore
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SyncRepository
import org.privatetracker.core.domain.usecase.server.AcceptPeer
import org.privatetracker.core.domain.usecase.server.AnswerPeerExchange
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.CreatePeerInvite
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.EvaluateGeofences
import org.privatetracker.core.domain.usecase.server.ExchangeWithPeer
import org.privatetracker.core.domain.usecase.server.GetAdvertisedAddresses
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.JoinPeer
import org.privatetracker.core.domain.usecase.server.ObservePeerJoined
import org.privatetracker.core.domain.usecase.server.ObservePeers
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.RemovePeer
import org.privatetracker.core.domain.usecase.server.SyncBatches
import org.privatetracker.core.domain.usecase.server.SyncWithPeers
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.usecase.server.WithdrawPeerInvite
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.network.KtorPeerGateway
import org.privatetracker.core.network.PinnedClients
import org.privatetracker.feature.server.platform.AndroidNetworkInfoProvider
import org.privatetracker.feature.server.service.ServiceServerController
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
import org.privatetracker.server.api.auth.InMemoryPeerTicketStore
import org.privatetracker.server.tunnel.TunnelVisitors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerBindings {
    @Binds abstract fun serverController(controller: ServiceServerController): ServerController

    @Binds abstract fun networkInfo(provider: AndroidNetworkInfoProvider): NetworkInfoProvider
}

@Module
@InstallIn(SingletonComponent::class)
object ServerModule {
    @Provides @Singleton fun nonces(clock: Clock): NonceRegistry = InMemoryNonceRegistry(clock::now)

    /** One store for the screen that shows the QR and the API that consumes its ticket. */
    @Provides @Singleton fun pairingTickets(): PairingTicketStore = InMemoryPairingTicketStore()

    /** The relay tunnel fills it in; the API asks it who a connection from this phone really is. */
    @Provides @Singleton fun tunnelVisitors(): TunnelVisitors = TunnelVisitors()

    @Provides @Singleton
    fun serverDependencies(
        appInfo: AppInfo,
        clock: Clock,
        config: ServerConfigRepository,
        serverKeys: ServerKeys,
        encryptionKeys: EncryptionKeyRing,
        register: RegisterOrUpdateDevice,
        authenticate: AuthenticateDevice,
        ingest: IngestLocationBatch,
        overviews: GetDeviceOverviews,
        detail: GetDeviceDetail,
        controller: ServiceServerController,
        advertised: GetAdvertisedAddresses,
        visitors: TunnelVisitors,
        acceptPeer: AcceptPeer,
        answerPeerExchange: AnswerPeerExchange,
    ) = ServerDependencies(
        serverVersion = appInfo.version,
        clock = clock,
        serverConfig = config,
        serverKeys = serverKeys,
        encryptionKeys = encryptionKeys,
        registerOrUpdateDevice = register,
        authenticateDevice = authenticate,
        ingestLocationBatch = ingest,
        getDeviceOverviews = overviews,
        getDeviceDetail = detail,
        isShuttingDown = { controller.shuttingDown },
        onRequest = controller::countRequest,
        advertisedAddresses = { advertised() },
        tunneledVisitor = visitors::visitor,
        acceptPeer = acceptPeer,
        answerPeerExchange = answerPeerExchange,
    )

    // Paired servers, from 1.0.

    /** Shared by the invite screen and the API that consumes its ticket. */
    @Provides @Singleton fun peerTickets(): PeerTicketStore = InMemoryPeerTicketStore()

    @Provides @Singleton
    fun peerGateway(clients: PinnedClients, serverKeys: ServerKeys, clock: Clock): PeerGateway = KtorPeerGateway(clients, serverKeys, clock)

    @Provides
    fun syncBatches(
        devices: DeviceRepository,
        sync: SyncRepository,
        peers: PeerRepository,
        config: ServerConfigRepository,
        verifier: SignatureVerifier,
        validator: LocationValidator,
        evaluateGeofences: EvaluateGeofences,
        clock: Clock,
    ) = SyncBatches(devices, sync, peers, config, verifier, validator, evaluateGeofences, clock)

    @Provides
    fun createPeerInvite(tickets: PeerTicketStore, serverKeys: ServerKeys, config: ServerConfigRepository, clock: Clock) =
        CreatePeerInvite(tickets, serverKeys, config, clock)

    @Provides fun withdrawPeerInvite(tickets: PeerTicketStore) = WithdrawPeerInvite(tickets)

    @Provides fun observePeerJoined(tickets: PeerTicketStore, peers: PeerRepository) = ObservePeerJoined(tickets, peers)

    @Provides fun observePeers(peers: PeerRepository) = ObservePeers(peers)

    @Provides fun removePeer(peers: PeerRepository) = RemovePeer(peers)

    @Provides
    fun acceptPeer(
        peers: PeerRepository,
        tickets: PeerTicketStore,
        serverKeys: ServerKeys,
        config: ServerConfigRepository,
        verifier: SignatureVerifier,
        verifySignature: VerifyRequestSignature,
        advertised: GetAdvertisedAddresses,
        clock: Clock,
    ) = AcceptPeer(peers, tickets, serverKeys, config, verifier, verifySignature, { advertised() }, clock)

    @Provides
    fun answerPeerExchange(
        peers: PeerRepository,
        batches: SyncBatches,
        verifySignature: VerifyRequestSignature,
        transactions: TransactionRunner,
        alerts: GeofenceAlerts,
        advertised: GetAdvertisedAddresses,
    ) = AnswerPeerExchange(peers, batches, verifySignature, transactions, alerts) { advertised() }

    @Provides
    fun joinPeer(
        peers: PeerRepository,
        gateway: PeerGateway,
        serverKeys: ServerKeys,
        config: ServerConfigRepository,
        advertised: GetAdvertisedAddresses,
        clock: Clock,
    ) = JoinPeer(peers, gateway, serverKeys, config, { advertised() }, clock)

    /** One for the app: its lock keeps two exchanges with the same server from running at once. */
    @Provides @Singleton
    fun exchangeWithPeer(
        peers: PeerRepository,
        batches: SyncBatches,
        gateway: PeerGateway,
        transactions: TransactionRunner,
        alerts: GeofenceAlerts,
        advertised: GetAdvertisedAddresses,
        clock: Clock,
    ) = ExchangeWithPeer(peers, batches, gateway, transactions, alerts, { advertised() }, clock)

    @Provides fun syncWithPeers(peers: PeerRepository, exchange: ExchangeWithPeer, clock: Clock) = SyncWithPeers(peers, exchange, clock)

    @Provides @Singleton
    fun advertisedAddresses(
        network: NetworkInfoProvider,
        config: ServerConfigRepository,
        controller: ServerController,
        fingerprint: GetServerKeyFingerprint,
        clock: Clock,
    ) = GetAdvertisedAddresses(network, config, controller, fingerprint, clock)
}
