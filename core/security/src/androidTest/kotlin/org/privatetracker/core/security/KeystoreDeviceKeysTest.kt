package org.privatetracker.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.protocol.crypto.EcdsaP256
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Real Keystore keys, checked with the same verifier the server uses. */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceKeysTest {
    private val deviceA = DeviceId.of(UUID.randomUUID().toString())
    private val deviceB = DeviceId.of(UUID.randomUUID().toString())

    @Test
    fun signaturesFromTheKeystoreVerifyWithTheServersVerifier() = runTest {
        val keys = KeystoreDeviceKeys(KeystoreKeys())
        val data = "POST /api/v1/devices/register".encodeToByteArray()

        val publicKey = keys.publicKey(deviceA)
        val signature = keys.sign(deviceA, data)

        assertTrue(EcdsaP256.isValidKey(publicKey))
        assertTrue(EcdsaP256.verify(publicKey, data, signature))
        assertFalse(EcdsaP256.verify(publicKey, "other".encodeToByteArray(), signature))
    }

    @Test
    fun theServerKeyIsItsOwnAndSignsLikeTheDeviceKeys() = runTest {
        val keys = KeystoreKeys()
        val server = KeystoreServerKeys(keys)
        val data = "PrivateTracker-server-v1".encodeToByteArray()

        assertTrue(EcdsaP256.verify(server.publicKey(), data, server.sign(data)))
        assertEquals(server.publicKey(), KeystoreServerKeys(KeystoreKeys()).publicKey())
        assertNotEquals(server.publicKey(), KeystoreDeviceKeys(keys).publicKey(deviceA))
    }

    @Test
    fun eachDeviceIdKeepsItsOwnKeyAcrossInstances() = runTest {
        val first = KeystoreDeviceKeys(KeystoreKeys()).publicKey(deviceA)

        assertEquals(first, KeystoreDeviceKeys(KeystoreKeys()).publicKey(deviceA))
        assertNotEquals(first, KeystoreDeviceKeys(KeystoreKeys()).publicKey(deviceB))
    }

    @Test
    fun aKeyFromBefore04IsReplacedOnlyWhenItServesTls() = runTest {
        // As 0.2 and 0.3 made them: SHA-256 only, which Android's TLS stack cannot sign with.
        fun legacyKey(alias: String): String {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EcdsaP256.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }
            return EcdsaP256.encode(generator.generateKeyPair().public)
        }

        // The server already knows a device's key: replacing it would lock the device out.
        val deviceKey = legacyKey("privatetracker-device-${deviceA.value}")
        assertEquals(deviceKey, KeystoreDeviceKeys(KeystoreKeys()).publicKey(deviceA))

        val oldServerKey = legacyKey("privatetracker-server")
        val newServerKey = KeystoreServerKeys(KeystoreKeys()).publicKey()
        assertNotEquals(oldServerKey, newServerKey)
        assertEquals(newServerKey, KeystoreServerKeys(KeystoreKeys()).publicKey())
    }
}
