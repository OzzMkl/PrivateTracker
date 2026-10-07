package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.encryptionKeyInput
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FakeEncryptionKeyVault
import org.privatetracker.core.domain.testing.FakeServerKeys
import org.privatetracker.core.domain.testing.FakeSignatureVerifier
import org.privatetracker.core.domain.testing.InMemoryEncryptionKeyRepository
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.fakeEncryptionKey
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptionKeyRingTest {
    private val clock = FakeClock()
    private val repository = InMemoryEncryptionKeyRepository()
    private val vault = FakeEncryptionKeyVault()
    private val ring = EncryptionKeyRing(repository, vault, FakeServerKeys(), clock)

    @Test
    fun `the first key lives a week and the server's identity key signs it`() = runTest {
        val signed = ring.current()

        assertEquals(fakeEncryptionKey(1), signed.key.publicKey)
        assertEquals(keyFingerprint(fakeEncryptionKey(1)), signed.key.id)
        assertEquals(T0.plus(Duration.ofDays(7)), signed.key.useUntil)
        val signature = Base64.getDecoder().decode(signed.signature)
        assertTrue(FakeSignatureVerifier.verify(FakeServerKeys.SERVER_KEY, encryptionKeyInput(signed.key), signature))
        assertEquals(signed, ring.current())
        assertEquals(1, vault.generated)
    }

    @Test
    fun `a new key is offered an hour before the old one ends, and the old one opens for a day after`() = runTest {
        val first = ring.current().key
        clock.current = T0.plus(Duration.ofDays(7)).minus(Duration.ofMinutes(61))
        assertEquals(first, ring.current().key)

        clock.advanceBy(Duration.ofMinutes(2))
        val second = ring.current().key
        assertEquals(fakeEncryptionKey(2), second.publicKey)
        assertNotNull(ring.decryptionKey(first.id))

        clock.current = first.useUntil.plus(Duration.ofDays(1)).minusSeconds(1)
        assertNotNull(ring.decryptionKey(first.id))
        clock.advanceBy(Duration.ofSeconds(1))
        assertNull(ring.decryptionKey(first.id))
        assertEquals(listOf(second), repository.get().map { it.key })
        // Destroyed in the key store too, so no copy left on storage opens.
        assertEquals(listOf("protected-1"), vault.destroyed)
    }

    @Test
    fun `rotating now deletes every older key at once`() = runTest {
        val first = ring.current().key

        val rotated = ring.rotate()

        assertEquals(fakeEncryptionKey(2), rotated.publicKey)
        assertEquals(rotated, ring.current().key)
        assertNull(ring.decryptionKey(first.id))
        assertNotNull(ring.decryptionKey(rotated.id))
        assertEquals(listOf(rotated), repository.get().map { it.key })
        assertEquals(listOf("protected-1"), vault.destroyed)
    }

    @Test
    fun `a key store failing in a key's last hour keeps offering the key that still serves`() = runTest {
        val first = ring.current().key
        clock.current = first.useUntil.minusSeconds(60)
        vault.failing = true

        assertEquals(first, ring.current().key)

        clock.current = first.useUntil
        assertFailsWith<DeviceKeyException> { ring.current() }
    }

    @Test
    fun `a stored key that no longer opens is dropped, and the next request gets a new one`() = runTest {
        val first = ring.current().key
        vault.failingToOpen = true

        assertNull(ring.decryptionKey(first.id))
        assertTrue(repository.get().isEmpty())

        vault.failingToOpen = false
        val second = ring.current().key
        assertEquals(fakeEncryptionKey(2), second.publicKey)
        assertNotNull(ring.decryptionKey(second.id))
    }

    @Test
    fun `a private key is opened once and kept in memory, an unknown id opens nothing`() = runTest {
        val key = ring.current().key

        ring.decryptionKey(key.id)
        ring.decryptionKey(key.id)

        assertEquals(listOf("protected-1"), vault.opened)
        assertNull(ring.decryptionKey("0000-0000-0000-0000"))
    }

    @Test
    fun `reading the current key writes nothing while no key expires`() = runTest {
        ring.current()
        val writes = repository.writes

        repeat(3) { ring.current() }

        assertEquals(writes, repository.writes)
    }

    @Test
    fun `a broken key store is reported, and the owner's rotation says so`() = runTest {
        vault.failing = true

        assertFailsWith<DeviceKeyException> { ring.current() }
        assertEquals(DomainError.DeviceKeyUnavailable, RotateEncryptionKey(ring)().failureError())
    }

    @Test
    fun `the server screen sees the first key made, then each rotation`() = runTest {
        val observe = ObserveEncryptionKey(ring)

        val first = observe().first()
        val rotated = ring.rotate()

        assertEquals(fakeEncryptionKey(1), first?.publicKey)
        assertEquals(rotated, observe().first())
    }
}
