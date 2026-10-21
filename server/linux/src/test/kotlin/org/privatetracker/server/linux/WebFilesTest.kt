package org.privatetracker.server.linux

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.server.hostkeys.OwnerOnly
import org.privatetracker.server.hostkeys.SelfSignedCertificate
import org.privatetracker.server.linux.keys.Der
import org.privatetracker.server.linux.keys.Pem
import org.privatetracker.server.linux.keys.PemFiles
import org.privatetracker.server.linux.keys.ReloadingKeyManager
import org.privatetracker.server.linux.keys.WebCertificateException
import org.privatetracker.server.linux.keys.WebKeyPair
import org.privatetracker.server.linux.keys.sha256
import org.privatetracker.server.linux.store.JsonFileWebAccountRepository
import org.privatetracker.server.linux.store.JsonFileWebSessionRepository
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PEM in each form tools write it. The keys are made here, at test time: none is kept in the repository. */
internal object PemWriter {
    fun pem(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"

    fun certificate(certificate: X509Certificate) = pem("CERTIFICATE", certificate.encoded)

    fun pkcs8(keys: KeyPair) = pem("PRIVATE KEY", keys.private.encoded)

    /** RFC 5915, as `openssl ecparam -genkey` writes it: the scalar and the curve's name. */
    fun sec1(keys: KeyPair): String {
        val scalar = (keys.private as ECPrivateKey).s.toByteArray().let { if (it.size > 32) it.takeLast(32).toByteArray() else ByteArray(32 - it.size) + it }
        val curve = Der.encode(0xa0, Der.encode(0x06, Der.oid("1.2.840.10045.3.1.7")))
        return pem("EC PRIVATE KEY", Der.sequence(Der.encode(0x02, byteArrayOf(1)), Der.encode(0x04, scalar), curve))
    }

    /** RFC 8017, as `openssl genrsa -traditional` writes it. */
    fun pkcs1(key: RSAPrivateCrtKey): String {
        fun integer(value: BigInteger) = Der.encode(0x02, value.toByteArray())
        return pem(
            "RSA PRIVATE KEY",
            Der.sequence(
                integer(BigInteger.ZERO), integer(key.modulus), integer(key.publicExponent), integer(key.privateExponent),
                integer(key.primeP), integer(key.primeQ), integer(key.primeExponentP), integer(key.primeExponentQ), integer(key.crtCoefficient),
            ),
        )
    }
}

class WebFilesTest {
    @TempDir
    lateinit var dir: Path

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private fun files(keys: KeyPair = EcdsaP256.generateKeyPair(), key: String = PemWriter.pkcs8(keys)): Pair<PemFiles, X509Certificate> {
        val certificate = SelfSignedCertificate.create(keys, now)
        val files = PemFiles(dir.resolve("fullchain.pem"), dir.resolve("privkey.pem"))
        Files.writeString(files.certificate, PemWriter.certificate(certificate))
        Files.writeString(files.key, key)
        return files to certificate
    }

    @Test
    fun `an EC key reads the same as PKCS 8 and as SEC1`() {
        val keys = EcdsaP256.generateKeyPair()

        val fromPkcs8 = Pem.privateKey(PemWriter.pkcs8(keys)) as ECPrivateKey
        val fromSec1 = Pem.privateKey(PemWriter.sec1(keys)) as ECPrivateKey

        assertEquals((keys.private as ECPrivateKey).s, fromPkcs8.s)
        assertEquals((keys.private as ECPrivateKey).s, fromSec1.s)
        // The curve came from the key's own [0] field.
        assertEquals((keys.private as ECPrivateKey).params.order, fromSec1.params.order)
    }

    @Test
    fun `an RSA key reads from PKCS 1 as from PKCS 8`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val original = keys.private as RSAPrivateCrtKey

        val fromPkcs1 = Pem.privateKey(PemWriter.pkcs1(original)) as RSAPrivateCrtKey
        val fromPkcs8 = Pem.privateKey(PemWriter.pkcs8(keys)) as RSAPrivateCrtKey

        assertEquals(original.privateExponent, fromPkcs1.privateExponent)
        assertEquals(original.modulus, fromPkcs8.modulus)
    }

    @Test
    fun `an encrypted key, a missing block or garbage are refused with a reason`() {
        assertContains(assertFailsWith<DeviceKeyException> { Pem.privateKey(PemWriter.pem("ENCRYPTED PRIVATE KEY", byteArrayOf(1, 2))) }.message!!, "encrypted")
        assertFailsWith<DeviceKeyException> { Pem.privateKey("nothing here") }
        assertFailsWith<DeviceKeyException> { Pem.certificates("") }
        val (files) = files(key = PemWriter.pem("PRIVATE KEY", byteArrayOf(0x30, 0x7f)))
        assertFailsWith<DeviceKeyException> { WebKeyPair.load(files) }
    }

    @Test
    fun `a key that is not the certificate's is refused before the server starts`() {
        val (files) = files(key = PemWriter.pkcs8(EcdsaP256.generateKeyPair()))

        val refused = assertFailsWith<WebCertificateException> { ReloadingKeyManager(files) }

        assertContains(refused.message!!, "does not belong")
    }

    @Test
    fun `a renewed certificate is picked up, a broken renewal keeps the old one`() {
        var time = now
        val clock = object : Clock() {
            override fun instant(): Instant = time
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val (files, first) = files()
        val manager = ReloadingKeyManager(files, clock, checkEvery = Duration.ofMinutes(1))
        val firstAlias = assertNotNull(manager.chooseEngineServerAlias("EC", null, null))
        assertEquals(first, manager.getCertificateChain(firstAlias)?.first())

        // Renewed in place, as certbot or tailscale cert do; file times move on.
        val renewedKeys = EcdsaP256.generateKeyPair()
        val renewed = SelfSignedCertificate.create(renewedKeys, now)
        Files.writeString(files.certificate, PemWriter.certificate(renewed))
        Files.writeString(files.key, PemWriter.pkcs8(renewedKeys))
        listOf(files.certificate, files.key).forEach { Files.setLastModifiedTime(it, FileTime.from(now.plusSeconds(30))) }
        assertEquals(firstAlias, manager.chooseEngineServerAlias("EC", null, null), "looked at again only after a minute")

        time = now.plusSeconds(61)
        val secondAlias = assertNotNull(manager.chooseEngineServerAlias("EC", null, null))
        assertNotEquals(firstAlias, secondAlias)
        assertEquals(renewed, manager.getCertificateChain(secondAlias)?.first())
        // A handshake that began before the renewal still finds its own pair.
        assertEquals(first, manager.getCertificateChain(firstAlias)?.first())

        Files.writeString(files.key, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n")
        Files.setLastModifiedTime(files.key, FileTime.from(now.plusSeconds(90)))
        time = now.plusSeconds(200)
        assertEquals(secondAlias, manager.chooseEngineServerAlias("EC", null, null))
        assertEquals(renewed, manager.pair.chain.first())
    }

    @Test
    fun `the certificate's fingerprint reads as browsers show it`() {
        val (_, certificate) = files()

        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

        assertEquals(expected, certificate.sha256())
        assertEquals(95, certificate.sha256().length)
    }

    @Test
    fun `an unreadable sessions file means signing in again, an unreadable accounts file stops the server`() = runTest {
        val sessionsFile = dir.resolve("web-sessions.json")
        val accountsFile = dir.resolve("web-accounts.json")
        Files.writeString(sessionsFile, """{"sessions":[{"tokenHash":"h","account":"ana"}]}""")
        Files.writeString(accountsFile, "{ no es json")

        assertNull(JsonFileWebSessionRepository(sessionsFile).get("h"))
        assertContains(assertFailsWith<java.io.IOException> { JsonFileWebAccountRepository(accountsFile) }.message!!, "respaldo")
        assertEquals("{ no es json", Files.readString(accountsFile))
    }

    @Test
    fun `web accounts and sessions survive a restart, owner-only`() = runTest {
        val accountsFile = dir.resolve("web-accounts.json")
        val sessionsFile = dir.resolve("web-sessions.json")
        val accounts = JsonFileWebAccountRepository(accountsFile)
        val sessions = JsonFileWebSessionRepository(sessionsFile)

        assertTrue(accounts.insert(WebAccount("ana", "pbkdf2-sha256$1\$c2FsdA\$aGFzaA", now, now)))
        assertTrue(!accounts.insert(WebAccount("ana", "otra", now, now)))
        sessions.add(WebSession("hash-1", "ana", now, now.plusSeconds(60), now))
        sessions.add(WebSession("hash-2", "ana", now, now.plusSeconds(3600), now))

        val reopened = JsonFileWebAccountRepository(accountsFile)
        val reopenedSessions = JsonFileWebSessionRepository(sessionsFile)
        assertEquals("ana", reopened.get("ana")?.name)
        assertEquals("ana", reopenedSessions.get("hash-2")?.account)
        assertEquals(now, reopenedSessions.get("hash-2")?.passwordSetAt)
        // Only over the hash it was read with: a change made meanwhile stands.
        assertTrue(!reopened.update(WebAccount("ana", "otro", now, now), ifHash = "no es el guardado"))
        assertEquals(1, reopenedSessions.deleteExpired(now.plusSeconds(60)))
        assertNull(reopenedSessions.get("hash-1"))
        assertEquals(1, reopenedSessions.deleteAll("ana"))
        assertTrue(reopened.delete("ana"))
        assertEquals(emptyList(), JsonFileWebAccountRepository(accountsFile).all())
        assertEquals(OwnerOnly.FILE, Files.getPosixFilePermissions(accountsFile))
        assertEquals(OwnerOnly.FILE, Files.getPosixFilePermissions(sessionsFile))
    }
}
