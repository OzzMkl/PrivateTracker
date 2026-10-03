package org.privatetracker.core.protocol.v1

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * How a tracker signs its requests, from 0.2 on. Every device route carries
 *
 *     Authorization: PrivateTracker-ECDSA device="<uuid>", created="<epoch seconds>", nonce="<base64url>", signature="<base64>"
 *
 * The signature (ECDSA P-256, SHA-256, DER) covers [signingInput]: method, path, device, time, nonce
 * and the SHA-256 of the exact body bytes, one per line. Health stays unsigned.
 */
object RequestSignature {
    const val SCHEME = "PrivateTracker-ECDSA"

    /** First line of the signed text, so a signature made for anything else never verifies here. */
    private const val CONTEXT = "PrivateTracker-ECDSA-v1"
    private const val ACK_CONTEXT = "PrivateTracker-ack-v1"
    private const val NONCE_BYTES = 16
    private const val MAX_NONCE_LENGTH = 64
    private val NONCE = Regex("^[A-Za-z0-9_-]+$")
    private val PARAMETER = Regex("""\s*([a-z]+)="([^"]*)"\s*""")

    class Parsed(val deviceId: String, val created: Long, val nonce: String, val signature: ByteArray)

    fun signingInput(method: String, path: String, deviceId: String, created: Long, nonce: String, body: ByteArray): ByteArray {
        val bodyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        return listOf(CONTEXT, method.uppercase(), path, deviceId, created.toString(), nonce, bodyHash)
            .joinToString("\n")
            .encodeToByteArray()
    }

    fun header(deviceId: String, created: Long, nonce: String, signature: ByteArray): String =
        "$SCHEME device=\"$deviceId\", created=\"$created\", nonce=\"$nonce\", " +
            "signature=\"${Base64.getEncoder().encodeToString(signature)}\""

    /** Null when [header] is not a well-formed signature of this scheme. */
    fun parse(header: String): Parsed? {
        val trimmed = header.trim()
        if (!trimmed.startsWith("$SCHEME ")) return null
        val parameters = trimmed.removePrefix(SCHEME).split(",").map { part ->
            val match = PARAMETER.matchEntire(part) ?: return null
            match.groupValues[1] to match.groupValues[2]
        }.toMap()
        val created = parameters["created"]?.toLongOrNull() ?: return null
        val nonce = parameters["nonce"]?.takeIf { it.length <= MAX_NONCE_LENGTH && NONCE.matches(it) } ?: return null
        val signature = parameters["signature"]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        val deviceId = parameters["device"] ?: return null
        return Parsed(deviceId, created, nonce, signature)
    }

    /**
     * From 0.3, the server signs its answer to a location batch with its own key, over the request's
     * nonce and the exact response body. A paired tracker deletes locations from its outbox only on a
     * signed acknowledgment, so a host that took over the address cannot make it drop them.
     */
    const val ACK_HEADER = "PrivateTracker-Ack-Signature"

    fun ackInput(nonce: String, responseBody: ByteArray): ByteArray {
        val bodyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(responseBody))
        return listOf(ACK_CONTEXT, nonce, bodyHash).joinToString("\n").encodeToByteArray()
    }

    fun newNonce(random: SecureRandom): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))
}
