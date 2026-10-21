package org.privatetracker.server.hostkeys

import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.crypto.EcdsaP256
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.X509ExtendedKeyManager

/** PKCS#12 files of a host. Their protection is the file mode (0600), not a password. */
object Pkcs12 {
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
 * An ECDSA P-256 identity key with its self-signed certificate, in the PKCS#12 file [path] under
 * [alias]. Made on first use and then never replaced: others pin it, so a file that no longer opens
 * is an error to fix by hand, not a reason to make a new identity. Every method throws
 * [DeviceKeyException] when the file cannot be read or written.
 */
class IdentityKeyFile(
    private val path: Path,
    private val alias: String,
    private val commonName: String,
    private val now: () -> Instant,
) {
    private val entry: KeyStore.PrivateKeyEntry by lazy { loadOrCreate() }

    /** Base64 X.509 SubjectPublicKeyInfo, as fingerprints and pins take it. */
    fun publicKey(): String = guarded { EcdsaP256.encode(entry().certificate.publicKey) }

    fun sign(data: ByteArray): ByteArray = guarded { EcdsaP256.sign(entry().privateKey, data) }

    fun certificate(): X509Certificate = guarded { entry().certificate as X509Certificate }

    /** For TLS: always this one key with its self-signed certificate, as server or as client. */
    fun keyManager(): X509ExtendedKeyManager = guarded {
        val current = entry()
        val store = Pkcs12.empty().apply { setKeyEntry(alias, current.privateKey, Pkcs12.PASSWORD, current.certificateChain) }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, Pkcs12.PASSWORD)
        factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().single()
    }

    /** The lazy entry, with a failure to load it reported as promised. */
    private fun entry(): KeyStore.PrivateKeyEntry = guarded { entry }

    private fun loadOrCreate(): KeyStore.PrivateKeyEntry =
        if (Files.exists(path)) {
            Pkcs12.load(path).getEntry(alias, Pkcs12.PROTECTION) as? KeyStore.PrivateKeyEntry
                ?: throw DeviceKeyException("$path holds no key named $alias")
        } else {
            val keys = EcdsaP256.generateKeyPair()
            val chain = arrayOf<Certificate>(SelfSignedCertificate.create(keys, now(), commonName = commonName))
            Pkcs12.save(Pkcs12.empty().apply { setKeyEntry(alias, keys.private, Pkcs12.PASSWORD, chain) }, path)
            KeyStore.PrivateKeyEntry(keys.private, chain)
        }
}

/** Runs [block], reporting key and file failures as [DeviceKeyException]. */
inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw DeviceKeyException("Key file unusable: ${e.message}", e)
} catch (e: IOException) {
    throw DeviceKeyException("Key file unreadable: ${e.message}", e)
}
