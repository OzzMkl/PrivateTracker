package org.privatetracker.core.protocol

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.protocol.crypto.InMemoryEncryptionKeyVault
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.core.protocol.v1.SealedBodies
import org.privatetracker.core.protocol.v1.SealedBodies.OpenResult
import org.privatetracker.core.protocol.v1.dto.SealedRequestDto
import org.privatetracker.core.protocol.v1.dto.SealedResponseDto
import java.security.PrivateKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SealedBodiesTest {
    private val vault = InMemoryEncryptionKeyVault()
    private val path = "/api/v1/devices/00000000-0000-4000-8000-000000000001/locations"
    private val body = """{"locations":[{"latitude":19.4326,"longitude":-99.1332}]}""".encodeToByteArray()

    private suspend fun aKey(): Pair<EncryptionKey, PrivateKey> {
        val generated = vault.generate()
        val key = EncryptionKey(assertNotNull(keyFingerprint(generated.publicKey)), generated.publicKey, T0.plusSeconds(3600))
        return key to vault.privateKey(generated.protectedPrivateKey)
    }

    private fun opener(vararg keys: Pair<EncryptionKey, PrivateKey>): suspend (String) -> Pair<PrivateKey, String>? = { id ->
        keys.firstOrNull { it.first.id == id }?.let { (key, private) -> private to key.publicKey }
    }

    @Test
    fun `a sealed body shows neither its content nor its shape, and opens only on the server`() = runTest {
        val key = aKey()
        val sealed = assertNotNull(SealedBodies.seal(key.first, "POST", path, body))

        val wire = sealed.body.decodeToString()
        assertFalse("latitude" in wire || "19.43" in wire, wire)
        val envelope = ProtocolJson.decodeFromString(SealedRequestDto.serializer(), wire)
        assertEquals(key.first.id, envelope.keyId)

        val opened = assertIs<OpenResult.Opened>(SealedBodies.open(sealed.body, "POST", path, opener(key)))
        assertContentEquals(body, opened.request.plaintext)
    }

    @Test
    fun `the answer opens only with the key of the request it answers`() = runTest {
        val key = aKey()
        val first = assertNotNull(SealedBodies.seal(key.first, "POST", path, body))
        val second = assertNotNull(SealedBodies.seal(key.first, "POST", path, body))
        val opened = assertIs<OpenResult.Opened>(SealedBodies.open(first.body, "POST", path, opener(key)))
        val answer = """{"accepted":["x"]}""".encodeToByteArray()

        val sealedAnswer = SealedBodies.sealResponse(opened.request, answer)

        assertFalse("accepted" in sealedAnswer.decodeToString())
        assertContentEquals(answer, SealedBodies.openResponse(first, sealedAnswer))
        assertNull(SealedBodies.openResponse(second, sealedAnswer))
        assertNull(SealedBodies.openResponse(first, """{"accepted":["x"]}""".encodeToByteArray()))
    }

    @Test
    fun `two answers to the same request never share a nonce`() = runTest {
        val key = aKey()
        val sealed = assertNotNull(SealedBodies.seal(key.first, "POST", path, body))
        // As when a captured request is replayed after the server restarted and forgot its nonce.
        val first = assertIs<OpenResult.Opened>(SealedBodies.open(sealed.body, "POST", path, opener(key))).request
        val again = assertIs<OpenResult.Opened>(SealedBodies.open(sealed.body, "POST", path, opener(key))).request

        val answers = listOf(SealedBodies.sealResponse(first, body), SealedBodies.sealResponse(again, body))

        val nonces = answers.map { ProtocolJson.decodeFromString(SealedResponseDto.serializer(), it.decodeToString()).nonce }
        assertNotEquals(nonces[0], nonces[1])
        answers.forEach { assertContentEquals(body, SealedBodies.openResponse(sealed, it)) }
    }

    @Test
    fun `a body moved to another route, altered, or sealed for a dropped key does not open`() = runTest {
        val key = aKey()
        val other = aKey()
        val sealed = assertNotNull(SealedBodies.seal(key.first, "POST", path, body))

        assertEquals(OpenResult.Unreadable, SealedBodies.open(sealed.body, "POST", "/api/v1/devices/register", opener(key)))
        val envelope = ProtocolJson.decodeFromString(SealedRequestDto.serializer(), sealed.body.decodeToString())
        val altered = envelope.copy(ciphertext = envelope.ciphertext.reversed())
        assertEquals(OpenResult.Unreadable, SealedBodies.open(encode(altered), "POST", path, opener(key)))
        // The envelope names a key the server holds, but the body was sealed for another one.
        val relabeled = envelope.copy(keyId = other.first.id)
        assertEquals(OpenResult.Unreadable, SealedBodies.open(encode(relabeled), "POST", path, opener(key, other)))
        assertEquals(OpenResult.UnknownKey(key.first.id), SealedBodies.open(sealed.body, "POST", path, opener(other)))
    }

    @Test
    fun `a plain body is recognized as unsealed, and broken JSON as such`() = runTest {
        val key = aKey()

        assertEquals(OpenResult.NotSealed, SealedBodies.open(body, "POST", path, opener(key)))
        assertEquals(OpenResult.Malformed, SealedBodies.open("""{"device_id": """.encodeToByteArray(), "POST", path, opener(key)))
        assertEquals(OpenResult.Malformed, SealedBodies.open("[1,2]".encodeToByteArray(), "POST", path, opener(key)))
        assertEquals(OpenResult.Unreadable, SealedBodies.open("""{"ciphertext":"x"}""".encodeToByteArray(), "POST", path, opener(key)))
    }

    @Test
    fun `nothing is sealed for a key that is not P-256`() {
        assertNull(SealedBodies.seal(EncryptionKey("X", "bm90IGEga2V5", T0), "POST", path, body))
    }

    private fun encode(envelope: SealedRequestDto): ByteArray =
        ProtocolJson.encodeToString(SealedRequestDto.serializer(), envelope).encodeToByteArray()
}
