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

/**
 * A fingerprint as someone typed it: spaces, dashes and colons dropped, letters upper-cased, then
 * grouped as [keyFingerprint] writes it. Text that is not 16 hex digits comes back trimmed, for the
 * validator to refuse.
 */
fun normalizeFingerprint(raw: String): String {
    val digits = raw.filterNot { it.isWhitespace() || it == '-' || it == ':' }.uppercase()
    val valid = digits.length == FINGERPRINT_BYTES * 2 && digits.all { it in '0'..'9' || it in 'A'..'F' }
    return if (valid) digits.chunked(4).joinToString("-") else raw.trim()
}

fun isValidFingerprint(value: String): Boolean = FINGERPRINT.matches(value)

private const val FINGERPRINT_BYTES = 8
private val FINGERPRINT = Regex("^[0-9A-F]{4}(-[0-9A-F]{4}){3}$")
