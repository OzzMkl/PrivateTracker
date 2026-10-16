package org.privatetracker.server.web.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.privatetracker.core.domain.port.PasswordHasher
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256, which every JDK has: OWASP's Password Storage Cheat Sheet recommends 600,000
 * iterations for it. Argon2id would need BouncyCastle or a native library for the same purpose.
 * Hashes read `pbkdf2-sha256$<iterations>$<salt>$<hash>`, so the settings can grow later and old
 * hashes still verify (and are redone at the next sign-in).
 *
 * At most [concurrency] hashes run at once, so a burst of sign-ins cannot take every core.
 */
class Pbkdf2PasswordHasher(
    private val iterations: Int = ITERATIONS,
    concurrency: Int = 2,
    private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {
    private val permits = Semaphore(concurrency)

    override suspend fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, iterations)
        return listOf(PREFIX, iterations.toString(), encode(salt), encode(hash)).joinToString("$")
    }

    override suspend fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        val count = parts.getOrNull(1)?.toIntOrNull()
        val salt = parts.getOrNull(2)?.let(::decode)
        val expected = parts.getOrNull(3)?.let(::decode)
        if (parts.size != 4 || parts[0] != PREFIX || count == null || count !in 1..MAX_ITERATIONS || salt == null || expected == null) {
            // Unreadable: still spend the time a real check takes.
            derive(password, ByteArray(SALT_BYTES), iterations)
            return false
        }
        return MessageDigest.isEqual(derive(password, salt, count), expected)
    }

    override fun isOutdated(encoded: String): Boolean {
        val parts = encoded.split('$')
        return parts.firstOrNull() != PREFIX || (parts.getOrNull(1)?.toIntOrNull() ?: 0) < iterations
    }

    private suspend fun derive(password: String, salt: ByteArray, count: Int): ByteArray = permits.withPermit {
        withContext(Dispatchers.Default) {
            val spec = PBEKeySpec(password.toCharArray(), salt, count, HASH_BYTES * 8)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(text: String) = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    companion object {
        const val ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val PREFIX = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
    }
}
