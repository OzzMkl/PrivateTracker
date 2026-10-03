package org.privatetracker.core.domain.model

import java.security.MessageDigest
import java.util.Base64

/**
 * A short digest of a public key that people compare on two screens before approving a device:
 * the first 64 bits of its SHA-256, as `3F9A-01BC-77D2-E410`. Null when [publicKey] is not Base64.
 */
fun keyFingerprint(publicKey: String): String? {
    val der = runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull() ?: return null
    return MessageDigest.getInstance("SHA-256").digest(der)
        .take(FINGERPRINT_BYTES)
        .joinToString("") { "%02X".format(it) }
        .chunked(4)
        .joinToString("-")
}

private const val FINGERPRINT_BYTES = 8
