package org.privatetracker.core.protocol.v1

import java.security.MessageDigest
import java.util.Base64

/**
 * How a server signs its requests to a paired one, from 1.0 on, with its identity key:
 *
 *     Authorization: PrivateTracker-Peer peer="<label>", created="<epoch seconds>", nonce="<base64url>", signature="<base64>"
 *
 * The label is the signer's key fingerprint without dashes, lower case. The signature (ECDSA P-256,
 * SHA-256, DER) covers [signingInput]: method, path, label, time, nonce and the SHA-256 of the body,
 * one per line, after a context line no tracker's signature starts with.
 */
object PeerSignature {
    const val SCHEME = "PrivateTracker-Peer"
    private const val CONTEXT = "PrivateTracker-peer-v1"
    private const val MAX_NONCE_LENGTH = 64
    private val NONCE = Regex("^[A-Za-z0-9_-]+$")
    private val LABEL = Regex("^[0-9a-f]{16}$")
    private val PARAMETER = Regex("""\s*([a-z]+)="([^"]*)"\s*""")

    class Parsed(val label: String, val created: Long, val nonce: String, val signature: ByteArray)

    fun signingInput(method: String, path: String, label: String, created: Long, nonce: String, body: ByteArray): ByteArray {
        val bodyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        return listOf(CONTEXT, method.uppercase(), path, label, created.toString(), nonce, bodyHash)
            .joinToString("\n")
            .encodeToByteArray()
    }

    fun header(label: String, created: Long, nonce: String, signature: ByteArray): String =
        "$SCHEME peer=\"$label\", created=\"$created\", nonce=\"$nonce\", " +
            "signature=\"${Base64.getEncoder().encodeToString(signature)}\""

    /** Null when [header] is not a well-formed signature of this scheme. */
    fun parse(header: String): Parsed? {
        val trimmed = header.trim()
        if (!trimmed.startsWith("$SCHEME ")) return null
        val parameters = trimmed.removePrefix(SCHEME).split(",").map { part ->
            val match = PARAMETER.matchEntire(part) ?: return null
            match.groupValues[1] to match.groupValues[2]
        }.toMap()
        val label = parameters["peer"]?.takeIf(LABEL::matches) ?: return null
        val created = parameters["created"]?.toLongOrNull() ?: return null
        val nonce = parameters["nonce"]?.takeIf { it.length <= MAX_NONCE_LENGTH && NONCE.matches(it) } ?: return null
        val signature = parameters["signature"]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        return Parsed(label, created, nonce, signature)
    }
}
