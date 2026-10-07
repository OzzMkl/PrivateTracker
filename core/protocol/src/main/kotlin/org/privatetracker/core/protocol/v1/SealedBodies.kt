package org.privatetracker.core.protocol.v1

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.protocol.crypto.Hpke
import org.privatetracker.core.protocol.crypto.P256
import org.privatetracker.core.protocol.crypto.aesGcm
import org.privatetracker.core.protocol.v1.dto.SealedRequestDto
import org.privatetracker.core.protocol.v1.dto.SealedResponseDto
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher

/**
 * End-to-end encryption of the device routes' bodies, from 0.5 on. The tracker seals each request body
 * with [Hpke] for the server's current encryption key, which the server's identity key signs. Whatever
 * sits between them, a relay or a proxy that ends TLS, sees only
 *
 *     {"key_id":"3F9A-01BC-77D2-E410","enc":"<Base64, 65 bytes>","ciphertext":"<Base64>"}
 *
 * The associated data binds the body to its method and path. The answer is sealed with a key both
 * sides export from that same HPKE context, so only the tracker that asked can read it:
 * `{"nonce":"<Base64, 12 bytes>","ciphertext":"<Base64>"}`. The nonce is random for every answer: a
 * request replayed after a server restart gets another answer under the same exported key. Problem
 * responses stay readable: they carry codes, never personal data. The request signature and the signed
 * acknowledgment cover these sealed bytes.
 *
 * Today TLS ends at the server itself, which the tracker pins; this layer keeps the bodies closed for
 * whatever forwards them later, such as the relay of 1.0, which will hold a TLS key of its own.
 */
object SealedBodies {
    private val INFO = "PrivateTracker-sealed-v1".encodeToByteArray()
    private val RESPONSE_LABEL = "PrivateTracker-response-v1".encodeToByteArray()
    private const val RESPONSE_KEY_LENGTH = 16
    private const val RESPONSE_NONCE_LENGTH = 12
    private val random = SecureRandom()

    /** The sealed body to send, and the key that opens its answer. */
    class SealedRequest(val body: ByteArray, internal val responseKey: ByteArray)

    /** A request the server could open, with the key to seal its answer. */
    class OpenedRequest(val plaintext: ByteArray, internal val responseKey: ByteArray)

    sealed interface OpenResult {
        data class Opened(val request: OpenedRequest) : OpenResult

        /** A body without the envelope: a tracker from before 0.5. */
        data object NotSealed : OpenResult

        /** Sealed for a key this server does not hold, or no longer does. */
        data class UnknownKey(val keyId: String) : OpenResult

        /** Not a JSON object at all. */
        data object Malformed : OpenResult

        /** An envelope that fails to open: altered on the way, or sealed for another key or path. */
        data object Unreadable : OpenResult
    }

    /** Tracker side. Null when [key]'s public key is not a P-256 key, which a vouched-for key always is. */
    fun seal(key: EncryptionKey, method: String, path: String, plaintext: ByteArray): SealedRequest? {
        val recipient = P256.decodePublicKey(key.publicKey) ?: return null
        val sender = Hpke.setupSender(recipient, INFO)
        val ciphertext = sender.seal(aad(method, path), plaintext)
        val envelope = SealedRequestDto(key.id, encode(sender.enc), encode(ciphertext))
        val body = ProtocolJson.encodeToString(SealedRequestDto.serializer(), envelope).encodeToByteArray()
        return SealedRequest(body, sender.export(RESPONSE_LABEL, RESPONSE_KEY_LENGTH))
    }

    /** Tracker side: the answer to [request], or null when it does not open with that request's key. */
    fun openResponse(request: SealedRequest, body: ByteArray): ByteArray? {
        val envelope = runCatching { ProtocolJson.decodeFromString(SealedResponseDto.serializer(), body.decodeToString()) }.getOrNull()
            ?: return null
        val nonce = decode(envelope.nonce)?.takeIf { it.size == RESPONSE_NONCE_LENGTH } ?: return null
        val ciphertext = decode(envelope.ciphertext) ?: return null
        return try {
            aesGcm(Cipher.DECRYPT_MODE, request.responseKey, nonce, RESPONSE_LABEL, ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /**
     * Server side. [privateKey] finds the private key and the public key of an id, or null for an id
     * the server does not hold; it may throw what the key store throws.
     */
    suspend fun open(
        body: ByteArray,
        method: String,
        path: String,
        privateKey: suspend (keyId: String) -> Pair<PrivateKey, String>?,
    ): OpenResult {
        val json = try {
            ProtocolJson.parseToJsonElement(body.decodeToString()).jsonObject
        } catch (e: SerializationException) {
            return OpenResult.Malformed
        } catch (e: IllegalArgumentException) {
            return OpenResult.Malformed
        }
        if (!json.isEnvelope()) return OpenResult.NotSealed
        val envelope = runCatching { ProtocolJson.decodeFromJsonElement(SealedRequestDto.serializer(), json) }.getOrNull()
            ?: return OpenResult.Unreadable
        val (key, publicKey) = privateKey(envelope.keyId) ?: return OpenResult.UnknownKey(envelope.keyId)
        val recipientPublicKey = P256.decodePublicKey(publicKey) ?: return OpenResult.UnknownKey(envelope.keyId)
        val enc = decode(envelope.enc) ?: return OpenResult.Unreadable
        val ciphertext = decode(envelope.ciphertext) ?: return OpenResult.Unreadable
        val recipient = Hpke.setupRecipient(enc, key, recipientPublicKey, INFO) ?: return OpenResult.Unreadable
        val plaintext = recipient.open(aad(method, path), ciphertext) ?: return OpenResult.Unreadable
        return OpenResult.Opened(OpenedRequest(plaintext, recipient.export(RESPONSE_LABEL, RESPONSE_KEY_LENGTH)))
    }

    /** Server side: [plaintext] sealed for the tracker that sent [request]. */
    fun sealResponse(request: OpenedRequest, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(RESPONSE_NONCE_LENGTH).also(random::nextBytes)
        val ciphertext = aesGcm(Cipher.ENCRYPT_MODE, request.responseKey, nonce, RESPONSE_LABEL, plaintext)
        return ProtocolJson.encodeToString(SealedResponseDto.serializer(), SealedResponseDto(encode(nonce), encode(ciphertext))).encodeToByteArray()
    }

    private fun JsonObject.isEnvelope(): Boolean = "ciphertext" in this

    private fun aad(method: String, path: String): ByteArray = "PrivateTracker-request-v1\n${method.uppercase()}\n$path".encodeToByteArray()

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray? = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
}
