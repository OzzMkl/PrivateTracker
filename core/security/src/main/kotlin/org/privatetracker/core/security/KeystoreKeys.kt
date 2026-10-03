package org.privatetracker.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.protocol.crypto.EcdsaP256
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ECDSA P-256 keys in the Android Keystore, created on first use. They need no user authentication,
 * so background services can sign. Private keys never leave the Keystore; on most phones they live
 * in secure hardware. Every failure surfaces as [DeviceKeyException].
 */
@Singleton
class KeystoreKeys @Inject constructor() {
    private val entries = mutableMapOf<String, KeyStore.PrivateKeyEntry>()

    suspend fun publicKey(alias: String): String = keystore { EcdsaP256.encode(entry(alias).certificate.publicKey) }

    suspend fun sign(alias: String, data: ByteArray): ByteArray = keystore { EcdsaP256.sign(entry(alias).privateKey, data) }

    /** Keystore calls block, and generating a key can take a moment on secure hardware. */
    private suspend fun <T> keystore(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyException("Keystore refused the key", e)
        } catch (e: ProviderException) {
            throw DeviceKeyException("Keystore failed", e)
        } catch (e: IOException) {
            throw DeviceKeyException("Keystore could not be loaded", e)
        }
    }

    private fun entry(alias: String): KeyStore.PrivateKeyEntry = synchronized(entries) {
        entries.getOrPut(alias) {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(alias)) generate(alias)
            keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: throw GeneralSecurityException("No private key under $alias")
        }
    }

    private fun generate(alias: String) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(EcdsaP256.CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/** One key per device id, so a new identity always comes with a new key. */
class KeystoreDeviceKeys @Inject constructor(private val keys: KeystoreKeys) : DeviceKeys {
    override suspend fun publicKey(deviceId: DeviceId): String = keys.publicKey(alias(deviceId))
    override suspend fun sign(deviceId: DeviceId, data: ByteArray): ByteArray = keys.sign(alias(deviceId), data)

    private fun alias(deviceId: DeviceId) = "privatetracker-device-${deviceId.value}"
}

/** The server's identity key, which paired trackers pin. */
class KeystoreServerKeys @Inject constructor(private val keys: KeystoreKeys) : ServerKeys {
    override suspend fun publicKey(): String = keys.publicKey(ALIAS)
    override suspend fun sign(data: ByteArray): ByteArray = keys.sign(ALIAS, data)

    private companion object {
        const val ALIAS = "privatetracker-server"
    }
}
