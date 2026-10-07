package org.privatetracker.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.protocol.crypto.EcdsaP256
import java.io.IOException
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.ProviderException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * ECDSA P-256 keys in the Android Keystore, created on first use. They need no user authentication,
 * so background services can sign. Private keys never leave the Keystore; on most phones they live
 * in secure hardware. Every failure surfaces as [DeviceKeyException].
 */
@Singleton
class KeystoreKeys @Inject constructor() {
    private val entries = mutableMapOf<String, KeyStore.PrivateKeyEntry>()

    /** [tls] marks a key that also serves TLS; one alias must always be used the same way. See [generate]. */
    suspend fun publicKey(alias: String, tls: Boolean = false): String =
        keystore { EcdsaP256.encode(entry(alias, tls).certificate.publicKey) }

    suspend fun sign(alias: String, data: ByteArray, tls: Boolean = false): ByteArray =
        keystore { EcdsaP256.sign(entry(alias, tls).privateKey, data) }

    /** The key and its self-signed certificate, for a TLS server. */
    suspend fun tlsEntry(alias: String): KeyStore.PrivateKeyEntry = keystore { entry(alias, tls = true) }

    private fun entry(alias: String, tls: Boolean): KeyStore.PrivateKeyEntry = synchronized(entries) {
        entries.getOrPut(alias) {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (existing == null || (tls && !signsRawDigests(existing))) {
                // A server key made before 0.4 allowed SHA-256 only, which TLS cannot use: it is replaced,
                // once, and trackers paired with it pair again. Device keys never need replacing.
                if (existing != null) keyStore.deleteEntry(alias)
                generate(alias, tls)
            }
            keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: throw GeneralSecurityException("No private key under $alias")
        }
    }

    private fun signsRawDigests(entry: KeyStore.PrivateKeyEntry): Boolean {
        val info = KeyFactory.getInstance(entry.privateKey.algorithm, PROVIDER).getKeySpec(entry.privateKey, KeyInfo::class.java)
        return KeyProperties.DIGEST_NONE in info.digests
    }

    /**
     * SHA-256 for the protocol's own signatures. A [tls] key also allows NONE, because Android's TLS
     * stack hashes first and then asks the key to sign the bare digest (NONEwithECDSA). The certificate
     * Android makes for the key is self-signed; trackers check the key in it, not its other fields.
     */
    private fun generate(alias: String, tls: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(EcdsaP256.CURVE))
            .setCertificateSubject(X500Principal("CN=PrivateTracker"))
        if (tls) builder.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE) else builder.setDigests(KeyProperties.DIGEST_SHA256)
        val spec = builder.build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/** Keystore calls block, and generating a key can take a moment on secure hardware. */
internal suspend fun <T> keystore(block: () -> T): T = withContext(Dispatchers.IO) {
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

/** One key per device id, so a new identity always comes with a new key. */
class KeystoreDeviceKeys @Inject constructor(private val keys: KeystoreKeys) : DeviceKeys {
    override suspend fun publicKey(deviceId: DeviceId): String = keys.publicKey(alias(deviceId))
    override suspend fun sign(deviceId: DeviceId, data: ByteArray): ByteArray = keys.sign(alias(deviceId), data)

    private fun alias(deviceId: DeviceId) = "privatetracker-device-${deviceId.value}"
}

/** The server's identity key, which paired trackers pin, and which also serves the server's TLS. */
class KeystoreServerKeys @Inject constructor(private val keys: KeystoreKeys) : ServerKeys {
    override suspend fun publicKey(): String = keys.publicKey(ALIAS, tls = true)
    override suspend fun sign(data: ByteArray): ByteArray = keys.sign(ALIAS, data, tls = true)

    /** For the TLS server: always this one key, never a device key the same Keystore holds. */
    suspend fun tlsKeyManager(): X509ExtendedKeyManager {
        val entry = keys.tlsEntry(ALIAS)
        @Suppress("UNCHECKED_CAST")
        return SingleKeyManager(ALIAS, entry.privateKey, entry.certificateChain as Array<X509Certificate>)
    }

    private companion object {
        const val ALIAS = "privatetracker-server"
    }
}

/** Offers exactly one key to every TLS client. */
private class SingleKeyManager(
    private val alias: String,
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun getPrivateKey(alias: String?): PrivateKey? = key.takeIf { alias == this.alias }
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain.takeIf { alias == this.alias }
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
