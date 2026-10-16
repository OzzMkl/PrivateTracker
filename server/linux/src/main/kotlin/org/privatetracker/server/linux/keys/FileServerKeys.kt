package org.privatetracker.server.linux.keys

import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.server.linux.writeAtomically
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509ExtendedKeyManager

/** PKCS#12 files of the Linux server. Their protection is the file mode (0600), not a password. */
internal object Pkcs12 {
    /** The format needs a password to write private and secret keys; this one is no secret, on purpose. */
    val PASSWORD: CharArray = "privatetracker".toCharArray()
    val PROTECTION = KeyStore.PasswordProtection(PASSWORD)

    fun empty(): KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }

    fun load(path: Path): KeyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, PASSWORD) } }

    fun save(store: KeyStore, path: Path) {
        val bytes = ByteArrayOutputStream().also { store.store(it, PASSWORD) }.toByteArray()
        writeAtomically(path, bytes)
    }
}

/**
 * The server's identity key on Linux, ECDSA P-256 like a phone server's Keystore key, in [path]. It is
 * made on first use and then never replaced: trackers pin it, so a file that no longer opens is an
 * error to fix by hand, not a reason to make a new identity. From 0.4 it is also the TLS key.
 * Every method throws [DeviceKeyException] when the file cannot be read or written.
 */
class FileServerKeys(private val path: Path, private val clock: Clock) : ServerKeys {
    private val entry: KeyStore.PrivateKeyEntry by lazy { loadOrCreate() }

    override suspend fun publicKey(): String = EcdsaP256.encode(entry().certificate.publicKey)

    override suspend fun sign(data: ByteArray): ByteArray = guarded { EcdsaP256.sign(entry().privateKey, data) }

    /** For the TLS server: always this one key with its self-signed certificate. */
    fun keyManager(): X509ExtendedKeyManager = guarded {
        val current = entry()
        val store = Pkcs12.empty().apply { setKeyEntry(ALIAS, current.privateKey, Pkcs12.PASSWORD, current.certificateChain) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, Pkcs12.PASSWORD)
        factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().single()
    }

    /** The self-signed certificate around the key: what the web port shows when the owner gave no other. */
    fun certificate(): X509Certificate = guarded { entry().certificate as X509Certificate }

    /** The lazy entry, with a failure to load it reported as the port promises. */
    private fun entry(): KeyStore.PrivateKeyEntry = guarded { entry }

    private fun loadOrCreate(): KeyStore.PrivateKeyEntry =
        if (Files.exists(path)) {
            Pkcs12.load(path).getEntry(ALIAS, Pkcs12.PROTECTION) as? KeyStore.PrivateKeyEntry
                ?: throw DeviceKeyException("$path holds no server key named $ALIAS")
        } else {
            val keys = EcdsaP256.generateKeyPair()
            val chain = arrayOf<Certificate>(SelfSignedCertificate.create(keys, clock.now()))
            Pkcs12.save(Pkcs12.empty().apply { setKeyEntry(ALIAS, keys.private, Pkcs12.PASSWORD, chain) }, path)
            KeyStore.PrivateKeyEntry(keys.private, chain)
        }

    private companion object {
        const val ALIAS = "privatetracker-server"
    }
}

internal inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw DeviceKeyException("Key file unusable: ${e.message}", e)
} catch (e: IOException) {
    throw DeviceKeyException("Key file unreadable: ${e.message}", e)
}
