package org.privatetracker.server.api

import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.plugin.rateLimitKey
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun `a nonce is refused while it can still verify and forgotten once it expired`() {
        val clock = FakeClock()
        val nonces = InMemoryNonceRegistry(clock::now)
        val expiry = T0.plus(Duration.ofMinutes(5))

        assertTrue(nonces.register(DEVICE_A, "n", expiry))
        assertFalse(nonces.register(DEVICE_A, "n", expiry))
        assertTrue(nonces.register(DEVICE_B, "n", expiry))

        clock.advanceBy(Duration.ofMinutes(5))
        assertTrue(nonces.register(DEVICE_A, "n", clock.now().plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `a full registry refuses new nonces instead of forgetting valid ones`() {
        val clock = FakeClock()
        val nonces = InMemoryNonceRegistry(clock::now, capacity = 2)
        val soon = T0.plusSeconds(10)
        val later = T0.plus(Duration.ofMinutes(5))

        assertTrue(nonces.register(DEVICE_A, "1", later))
        assertTrue(nonces.register(DEVICE_A, "2", soon))
        assertFalse(nonces.register(DEVICE_A, "3", later))
        assertFalse(nonces.register(DEVICE_A, "1", later))

        // Once one expires there is room again, and the other is still remembered.
        clock.advanceBy(Duration.ofSeconds(10))
        assertTrue(nonces.register(DEVICE_A, "3", later))
        assertFalse(nonces.register(DEVICE_A, "1", later))
    }

    @Test
    fun `the request budget belongs to an address and a device, whatever the case of the id`() {
        val id = DEVICE_A.value
        assertEquals(rateLimitKey("10.0.0.2", id), rateLimitKey("10.0.0.2", id.uppercase()))
        assertTrue(rateLimitKey("10.0.0.2", id) != rateLimitKey("10.0.0.66", id))
        assertEquals("10.0.0.2|", rateLimitKey("10.0.0.2", null))
    }
}
