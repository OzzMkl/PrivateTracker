package org.privatetracker.feature.server.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.port.NetworkInfoProvider
import org.privatetracker.core.domain.port.NonceRegistry
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.feature.server.platform.AndroidNetworkInfoProvider
import org.privatetracker.feature.server.service.ServiceServerController
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
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
    )
}
