package org.privatetracker.server.web

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.privatetracker.server.web.security.Pbkdf2PasswordHasher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Pbkdf2PasswordHasherTest {
    // Few iterations: the format and the checks are what is tested here, not the cost.
    private val hasher = Pbkdf2PasswordHasher(iterations = 1_000)

    @Test
    fun `a hash verifies its password only, and two hashes of one password differ`() = runTest {
        val first = hasher.hash("caballo bateria grapa correcta")
        val second = hasher.hash("caballo bateria grapa correcta")

        assertTrue(first.startsWith("pbkdf2-sha256$1000$"))
        assertNotEquals(first, second)
        assertTrue(hasher.verify("caballo bateria grapa correcta", first))
        assertTrue(hasher.verify("caballo bateria grapa correcta", second))
        assertFalse(hasher.verify("caballo bateria grapa correcta.", first))
        assertFalse(hasher.verify("", first))
    }

    @Test
    fun `a hash with fewer iterations still verifies and is due for a redo`() = runTest {
        val old = hasher.hash("caballo bateria grapa correcta")
        val stronger = Pbkdf2PasswordHasher(iterations = 2_000)

        assertTrue(stronger.verify("caballo bateria grapa correcta", old))
        assertTrue(stronger.isOutdated(old))
        assertFalse(hasher.isOutdated(old))
        assertTrue(hasher.isOutdated("bcrypt$10\$whatever"))
    }

    @Test
    fun `hashes it cannot read verify nothing`() = runTest {
        listOf("", "pbkdf2-sha256", "pbkdf2-sha256$0\$AA\$AA", "pbkdf2-sha256$99999999\$AA\$AA", "pbkdf2-sha256$1000$!!!\$AA", "md5\$abc")
            .forEach { assertFalse(hasher.verify("x", it), it) }
    }

    @Test
    fun `the default costs what OWASP asks for PBKDF2-HMAC-SHA256`() = runTest {
        assertEquals(600_000, Pbkdf2PasswordHasher.ITERATIONS)
        val started = System.nanoTime()
        val hash = Pbkdf2PasswordHasher().hash("caballo bateria grapa correcta")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(hash.startsWith("pbkdf2-sha256$600000$"))
        println("One PBKDF2 hash with 600,000 iterations took $millis ms on this machine")
    }
}
