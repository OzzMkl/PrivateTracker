package org.privatetracker.core.security.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.security.KeystoreDeviceKeys
import org.privatetracker.core.security.KeystoreServerKeys

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityBindings {
    @Binds abstract fun deviceKeys(keys: KeystoreDeviceKeys): DeviceKeys

    @Binds abstract fun serverKeys(keys: KeystoreServerKeys): ServerKeys
}

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    /** The server checks signatures with plain JCA, which Android provides on every supported version. */
    @Provides fun signatureVerifier(): SignatureVerifier = EcdsaP256
}
