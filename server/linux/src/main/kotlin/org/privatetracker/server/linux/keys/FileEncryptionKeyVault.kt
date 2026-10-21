package org.privatetracker.server.linux.keys

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.EncryptionKeyVault
import org.privatetracker.core.domain.port.GeneratedEncryptionKey
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.P256
import org.privatetracker.server.hostkeys.Pkcs12
import org.privatetracker.server.hostkeys.guarded
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The server's encryption keys on Linux, kept as a phone server keeps them: each private key encrypted
 * with an AES-256-GCM key of its own. Here those AES keys live in the PKCS#12 file [path] (0600), apart
 * from the key list. Destroying a key deletes its AES key from that file, so the stored blob, and any
 * copy of the key list in a backup, no longer opens; only a copy of the vault file made before could.
 *
 * The stored form is `<alias>:<Base64 of IV, ciphertext and tag>`, the same as on a phone.
 */
class FileEncryptionKeyVault(private val path: Path, private val random: SecureRandom = SecureRandom()) : EncryptionKeyVault {
    private val mutex = Mutex()
    private var store: KeyStore? = null

    override suspend fun generate(): GeneratedEncryptionKey = vault { store ->
        val pair = P256.generateKeyPair()
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        val key = KeyGenerator.getInstance("AES").apply { init(KEY_BITS, random) }.generateKey()
        store.setEntry(alias, KeyStore.SecretKeyEntry(key), Pkcs12.PROTECTION)
        Pkcs12.save(store, path)
        GeneratedEncryptionKey(P256.encodePublicKey(pair.public as ECPublicKey), "$alias:" + wrap(key, pair.private.encoded))
    }

    override suspend fun privateKey(protectedPrivateKey: String): PrivateKey = vault { store ->
        val (alias, wrapped) = parse(protectedPrivateKey)
        val key = store.getKey(alias, Pkcs12.PASSWORD) as? SecretKey ?: throw DeviceKeyException("The key that protects $alias is gone")
        KeyFactory.getInstance(EcdsaP256.KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(unwrap(key, wrapped)))
    }

    override suspend fun destroy(protectedPrivateKey: String) = vault { store ->
        val (alias, _) = parse(protectedPrivateKey)
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
            Pkcs12.save(store, path)
        }
    }

    /** One change at a time, on the file as last read; a failed save leaves the file as it was. */
    private suspend fun <T> vault(block: (KeyStore) -> T): T = mutex.withLock {
        guarded {
            val current = store ?: (if (Files.exists(path)) Pkcs12.load(path) else Pkcs12.empty()).also { store = it }
            try {
                block(current)
            } catch (e: Exception) {
                // An entry set or deleted in memory but not saved must not linger: read the file again next time.
                store = null
                throw e
            }
        }
    }

    /** A fresh IV, then ciphertext and tag. */
    private fun wrap(key: SecretKey, plain: ByteArray): String {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(CONTEXT)
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain))
    }

    private fun unwrap(key: SecretKey, bytes: ByteArray): ByteArray {
        if (bytes.size <= IV_LENGTH) throw DeviceKeyException("Stored encryption key is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
        cipher.updateAAD(CONTEXT)
        return cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
    }

    private fun parse(protectedPrivateKey: String): Pair<String, ByteArray> {
        val alias = protectedPrivateKey.substringBefore(':', "")
        if (!alias.startsWith(ALIAS_PREFIX)) throw DeviceKeyException("Stored encryption key names no vault key")
        val wrapped = try {
            Base64.getDecoder().decode(protectedPrivateKey.substringAfter(':'))
        } catch (e: IllegalArgumentException) {
            throw DeviceKeyException("Stored encryption key is not Base64", e)
        }
        return alias to wrapped
    }

    private companion object {
        const val ALIAS_PREFIX = "privatetracker-encryption-"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        val CONTEXT = "PrivateTracker-encryption-key-wrap-v1".encodeToByteArray()
    }
}
