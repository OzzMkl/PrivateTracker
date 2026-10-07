package org.privatetracker.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.crypto.P256
import org.privatetracker.core.protocol.v1.SealedBodies
import java.time.Instant
import java.util.Base64

/** The server's encryption keys on a real Keystore, opening what a tracker sealed with the protocol's own code. */
@RunWith(AndroidJUnit4::class)
class KeystoreEncryptionKeyVaultTest {
    private val path = "/api/v1/devices/register"
    private val body = """{"name":"Pixel de Ana"}""".encodeToByteArray()

    @Test
    fun aKeyMadeHereOpensWhatATrackerSealedForIt() = runTest {
        val vault = KeystoreEncryptionKeyVault()
        val generated = vault.generate()
        val key = EncryptionKey(keyFingerprint(generated.publicKey)!!, generated.publicKey, Instant.now().plusSeconds(60))
        assertNotNull(P256.decodePublicKey(generated.publicKey))

        val sealed = SealedBodies.seal(key, "POST", path, body)!!
        // Another instance, as after a restart: only the Keystore's wrapping key and the stored copy remain.
        val privateKey = KeystoreEncryptionKeyVault().privateKey(generated.protectedPrivateKey)
        val opened = SealedBodies.open(sealed.body, "POST", path) { id -> (privateKey to generated.publicKey).takeIf { id == key.id } }

        assertArrayEquals(body, (opened as SealedBodies.OpenResult.Opened).request.plaintext)
    }

    @Test
    fun theStoredCopyNeverHoldsThePrivateKeyInTheClear() = runTest {
        val vault = KeystoreEncryptionKeyVault()
        val generated = vault.generate()
        val (alias, blob) = generated.protectedPrivateKey.split(':')
        val stored = Base64.getDecoder().decode(blob)
        val pkcs8 = vault.privateKey(generated.protectedPrivateKey).encoded

        assertTrue(alias.startsWith("privatetracker-encryption-"))
        assertFalse(stored.toList().windowed(pkcs8.size).any { it == pkcs8.toList() })
        assertEquals(12 + pkcs8.size + 16, stored.size)
    }

    @Test
    fun aDestroyedKeyNeverOpensAgainWhileOthersStillDo() = runTest {
        val vault = KeystoreEncryptionKeyVault()
        val kept = vault.generate()
        val gone = vault.generate()

        vault.destroy(gone.protectedPrivateKey)

        assertThrows(DeviceKeyException::class.java) { runBlocking { vault.privateKey(gone.protectedPrivateKey) } }
        assertNotNull(vault.privateKey(kept.protectedPrivateKey))
        vault.destroy(kept.protectedPrivateKey)
    }

    @Test
    fun anAlteredStoredCopyIsAKeyStoreFailure() = runTest {
        val vault = KeystoreEncryptionKeyVault()
        val (alias, blob) = vault.generate().protectedPrivateKey.split(':')
        val stored = Base64.getDecoder().decode(blob)
        stored[stored.size - 1] = (stored.last().toInt() xor 1).toByte()

        assertThrows(DeviceKeyException::class.java) { runBlocking { vault.privateKey("$alias:" + Base64.getEncoder().encodeToString(stored)) } }
        assertThrows(DeviceKeyException::class.java) { runBlocking { vault.privateKey("$alias:not base64!") } }
        assertThrows(DeviceKeyException::class.java) { runBlocking { vault.privateKey(blob) } }
    }
}
