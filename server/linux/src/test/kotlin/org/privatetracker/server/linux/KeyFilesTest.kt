package org.privatetracker.server.linux

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.StoredEncryptionKey
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.server.hostkeys.OwnerOnly
import org.privatetracker.server.hostkeys.SelfSignedCertificate
import org.privatetracker.server.linux.keys.FileEncryptionKeyVault
import org.privatetracker.server.linux.keys.FileServerKeys
import org.privatetracker.server.linux.store.JsonFileEncryptionKeyRepository
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyFilesTest {
    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock = Clock { now }

    private fun mode(path: Path) = Files.getPosixFilePermissions(path)

    @Test
    fun `the self-signed certificate parses, verifies with its own key and never expires`() {
        val keys = EcdsaP256.generateKeyPair()

        val certificate = SelfSignedCertificate.create(keys, now, SecureRandom())

        certificate.verify(keys.public)
        assertContentEquals(keys.public.encoded, certificate.publicKey.encoded)
        assertEquals(3, certificate.version)
        assertEquals("SHA256withECDSA", certificate.sigAlgName)
        assertEquals("CN=PrivateTracker Server", certificate.subjectX500Principal.name)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertEquals(now.minusSeconds(86_400), certificate.notBefore.toInstant())
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"), certificate.notAfter.toInstant())
        assertTrue(certificate.serialNumber.signum() > 0)
    }

    @Test
    fun `the identity key is made once, only its owner can read it, and it comes back the same`() = runTest {
        val file = dir.resolve("identity.p12")
        val first = FileServerKeys(file, clock)

        val key = first.publicKey()
        val signature = first.sign("hola".encodeToByteArray())

        assertEquals(OwnerOnly.FILE, mode(file))
        assertTrue(EcdsaP256.verify(key, "hola".encodeToByteArray(), signature))
        assertEquals(key, FileServerKeys(file, clock).publicKey())
        // The TLS key is that same key.
        val manager = first.keyManager()
        val alias = manager.getServerAliases("EC", null).single()
        assertContentEquals(Base64.getDecoder().decode(key), manager.getCertificateChain(alias).single().publicKey.encoded)
    }

    @Test
    fun `an identity file that no longer opens is reported, never replaced`() = runTest {
        val file = dir.resolve("identity.p12")
        Files.write(file, byteArrayOf(1, 2, 3))

        assertFailsWith<DeviceKeyException> { FileServerKeys(file, clock).publicKey() }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(file))
    }

    @Test
    fun `encryption keys open after a restart, and a destroyed one never again`() = runTest {
        val file = dir.resolve("vault.p12")
        val vault = FileEncryptionKeyVault(file)
        val kept = vault.generate()
        val dropped = vault.generate()

        vault.destroy(dropped.protectedPrivateKey)

        assertEquals(OwnerOnly.FILE, mode(file))
        val reopened = FileEncryptionKeyVault(file)
        assertEquals("EC", reopened.privateKey(kept.protectedPrivateKey).algorithm)
        assertFailsWith<DeviceKeyException> { reopened.privateKey(dropped.protectedPrivateKey) }
        // The blob alone, as a backup of the key list holds it, opens nothing without the vault.
        assertFailsWith<DeviceKeyException> { FileEncryptionKeyVault(dir.resolve("other.p12")).privateKey(kept.protectedPrivateKey) }
    }

    @Test
    fun `a tampered encryption key blob is refused`() = runTest {
        val vault = FileEncryptionKeyVault(dir.resolve("vault.p12"))
        val generated = vault.generate()
        val alias = generated.protectedPrivateKey.substringBefore(':')
        val bytes = Base64.getDecoder().decode(generated.protectedPrivateKey.substringAfter(':'))
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()

        assertFailsWith<DeviceKeyException> { vault.privateKey("$alias:" + Base64.getEncoder().encodeToString(bytes)) }
    }

    @Test
    fun `the key list survives a restart and is written owner-only`() = runTest {
        val file = dir.resolve("keys.json")
        val repository = JsonFileEncryptionKeyRepository(file)
        val stored = StoredEncryptionKey(EncryptionKey("ABCD-EF01", "cHVibGlj", now.plusSeconds(3600)), now, "alias:blob")

        repository.update { it + stored }

        assertEquals(OwnerOnly.FILE, mode(file))
        assertEquals(listOf(stored), JsonFileEncryptionKeyRepository(file).get())
    }
}
