package org.privatetracker.core.protocol

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.v1.RequestSignature
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureTest {
    private val body = """{"locations":[]}""".encodeToByteArray()

    private fun input(path: String = "/api/v1/devices/x/locations", body: ByteArray = this.body, nonce: String = "n1") =
        RequestSignature.signingInput("post", path, DEVICE_A.value, 1_790_000_000, nonce, body)

    @Test
    fun `the signed text names every part of the request, one per line`() {
        val lines = input().decodeToString().lines()

        assertEquals("PrivateTracker-ECDSA-v1", lines[0])
        assertEquals(listOf("POST", "/api/v1/devices/x/locations", DEVICE_A.value, "1790000000", "n1"), lines.subList(1, 6))
        // SHA-256 of the body, Base64.
        assertEquals(44, lines[6].length)
    }

    @Test
    fun `a device's signature verifies only for the exact request it signed`() = runTest {
        val keys = InMemoryDeviceKeys()
        val key = keys.publicKey(DEVICE_A)
        val signature = keys.sign(DEVICE_A, input())

        assertTrue(EcdsaP256.verify(key, input(), signature))
        assertFalse(EcdsaP256.verify(key, input(path = "/api/v1/devices/y/locations"), signature))
        assertFalse(EcdsaP256.verify(key, input(body = """{"locations":[{}]}""".encodeToByteArray()), signature))
        assertFalse(EcdsaP256.verify(key, input(nonce = "n2"), signature))
        assertFalse(EcdsaP256.verify(keys.publicKey(DEVICE_B), input(), signature))
        assertFalse(EcdsaP256.verify(key, input(), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `each device keeps one key, and only P-256 keys are valid`() = runTest {
        val keys = InMemoryDeviceKeys()
        assertEquals(keys.publicKey(DEVICE_A), keys.publicKey(DEVICE_A))
        assertTrue(keys.publicKey(DEVICE_A) != keys.publicKey(DEVICE_B))
        assertTrue(EcdsaP256.isValidKey(keys.publicKey(DEVICE_A)))

        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertFalse(EcdsaP256.isValidKey(EcdsaP256.encode(p384.public)))
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(EcdsaP256.isValidKey(Base64.getEncoder().encodeToString(rsa.public.encoded)))
        assertFalse(EcdsaP256.isValidKey("not base64 at all"))
        assertNotNull(keyFingerprint(keys.publicKey(DEVICE_A)))
    }

    @Test
    fun `the Authorization header round-trips`() {
        val nonce = RequestSignature.newNonce(SecureRandom())
        val header = RequestSignature.header(DEVICE_A.value, 1_790_000_000, nonce, byteArrayOf(9, 8, 7))

        val parsed = assertNotNull(RequestSignature.parse(header))

        assertEquals(DEVICE_A.value, parsed.deviceId)
        assertEquals(1_790_000_000, parsed.created)
        assertEquals(nonce, parsed.nonce)
        assertContentEquals(byteArrayOf(9, 8, 7), parsed.signature)
    }

    @Test
    fun `headers of another scheme or with missing or odd parameters are not signatures`() {
        val valid = RequestSignature.header(DEVICE_A.value, 1, "abc_-", byteArrayOf(1))
        assertNotNull(RequestSignature.parse(valid))

        listOf(
            "Bearer abc",
            valid.replace("PrivateTracker-ECDSA", "PrivateTracker-RSA"),
            valid.replace("""created="1"""", """created="soon""""),
            valid.replace("""nonce="abc_-"""", """nonce="a b""""),
            valid.replace("""nonce="abc_-"""", "nonce=\"${"a".repeat(65)}\""),
            valid.replace(""", signature="AQ=="""", ""),
            valid.replace("""signature="AQ=="""", """signature="%%%""""),
            "PrivateTracker-ECDSA device=unquoted",
        ).forEach { assertNull(RequestSignature.parse(it), it) }
    }
}
