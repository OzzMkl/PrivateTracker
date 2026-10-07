package org.privatetracker.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.EncryptionKeyVault
import org.privatetracker.core.domain.port.GeneratedEncryptionKey
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.P256
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server's encryption keys (ECDH P-256). They are made in software, because the Keystore only
 * agrees keys from Android 12 (API 31) on. Each private key is then encrypted with an AES-256-GCM key
 * of its own that lives in the Keystore and never leaves it, so the stored copy opens on this phone
 * only. Destroying a key deletes that Keystore key: any copy of the stored blob left on flash, or in
 * an old backup of the file, can no longer be opened.
 *
 * The stored form is `<Keystore alias>:<Base64 of IV, ciphertext and tag>`.
 */
@Singleton
class KeystoreEncryptionKeyVault @Inject constructor() : EncryptionKeyVault {
    override suspend fun generate(): GeneratedEncryptionKey = keystore {
        val pair = P256.generateKeyPair()
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        GeneratedEncryptionKey(P256.encodePublicKey(pair.public as ECPublicKey), "$alias:" + wrap(newWrappingKey(alias), pair.private.encoded))
    }

    override suspend fun privateKey(protectedPrivateKey: String): PrivateKey = keystore {
        val (alias, wrapped) = parse(protectedPrivateKey)
        val key = keyStore().getKey(alias, null) as? SecretKey ?: throw DeviceKeyException("The key that protects $alias is gone")
        KeyFactory.getInstance(EcdsaP256.KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(unwrap(key, wrapped)))
    }

    override suspend fun destroy(protectedPrivateKey: String) = keystore {
        val (alias, _) = parse(protectedPrivateKey)
        keyStore().deleteEntry(alias)
    }

    /** IV, then ciphertext and tag. The Keystore picks the IV itself. */
    private fun wrap(key: SecretKey, plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(CONTEXT)
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
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
        if (!alias.startsWith(ALIAS_PREFIX)) throw DeviceKeyException("Stored encryption key names no Keystore key")
        val wrapped = try {
            Base64.getDecoder().decode(protectedPrivateKey.substringAfter(':'))
        } catch (e: IllegalArgumentException) {
            throw DeviceKeyException("Stored encryption key is not Base64", e)
        }
        return alias to wrapped
    }

    private fun newWrappingKey(alias: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
            generateKey()
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS_PREFIX = "privatetracker-encryption-"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        val CONTEXT = "PrivateTracker-encryption-key-wrap-v1".encodeToByteArray()
    }
}
