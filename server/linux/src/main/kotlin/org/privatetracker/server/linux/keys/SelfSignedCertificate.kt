package org.privatetracker.server.linux.keys

import org.privatetracker.core.protocol.crypto.EcdsaP256
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The X.509 certificate TLS needs around the server's key: version 3, self-signed with ECDSA-SHA256,
 * no extensions. Trackers trust the key inside it and nothing else (no authority, name or dates; see
 * PinnedKeyTrustManager), so it only has to be well formed. The JDK has no public API to build one,
 * and a few lines of DER spare the server BouncyCastle.
 */
object SelfSignedCertificate {
    private const val COMMON_NAME = "PrivateTracker Server"

    // RFC 5280: notAfter 9999-12-31T23:59:59Z means the certificate has no expiry date.
    private val NO_EXPIRY: Instant = Instant.parse("9999-12-31T23:59:59Z")

    fun create(keys: KeyPair, now: Instant, random: SecureRandom = SecureRandom()): X509Certificate {
        val name = sequence(set(sequence(oid(OID_COMMON_NAME), utf8String(COMMON_NAME))))
        val algorithm = sequence(oid(OID_ECDSA_WITH_SHA256))
        val tbs = sequence(
            explicit(0, integer(byteArrayOf(2))),
            integer(serialNumber(random)),
            algorithm,
            name,
            sequence(time(now.minusSeconds(86_400)), time(NO_EXPIRY)),
            name,
            keys.public.encoded,
        )
        val signature = EcdsaP256.sign(keys.private, tbs)
        val der = sequence(tbs, algorithm, bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    /** 16 random bytes, positive and with no leading zero, so DER needs no padding byte. */
    private fun serialNumber(random: SecureRandom): ByteArray = ByteArray(16).also {
        random.nextBytes(it)
        it[0] = ((it[0].toInt() and 0x3f) or 0x40).toByte()
    }

    // DER, only what a certificate needs.

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"

    private fun sequence(vararg parts: ByteArray) = tagged(0x30, *parts)
    private fun set(vararg parts: ByteArray) = tagged(0x31, *parts)
    private fun explicit(tag: Int, part: ByteArray) = tagged(0xa0 or tag, part)
    private fun integer(value: ByteArray) = tagged(0x02, value)
    private fun utf8String(value: String) = tagged(0x0c, value.encodeToByteArray())
    private fun bitString(value: ByteArray) = tagged(0x03, byteArrayOf(0), value)

    /** UTCTime until 2049, GeneralizedTime after, as RFC 5280 asks. */
    private fun time(instant: Instant): ByteArray {
        val utc = instant.atOffset(ZoneOffset.UTC)
        return if (utc.year < 2050) {
            tagged(0x17, utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).encodeToByteArray())
        } else {
            tagged(0x18, utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).encodeToByteArray())
        }
    }

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map(String::toLong)
        val body = ByteArrayOutputStream()
        body.writeBase128(arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { body.writeBase128(it) }
        return tagged(0x06, body.toByteArray())
    }

    private fun ByteArrayOutputStream.writeBase128(value: Long) {
        val groups = generateSequence(value) { (it ushr 7).takeIf { rest -> rest > 0 } }.map { (it and 0x7f).toInt() }.toList().reversed()
        groups.forEachIndexed { index, group -> write(if (index < groups.lastIndex) group or 0x80 else group) }
    }

    private fun tagged(tag: Int, vararg parts: ByteArray): ByteArray {
        val length = parts.sumOf { it.size }
        val out = ByteArrayOutputStream(length + 6)
        out.write(tag)
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> { out.write(0x81); out.write(length) }
            else -> { out.write(0x82); out.write(length ushr 8); out.write(length and 0xff) }
        }
        parts.forEach(out::write)
        return out.toByteArray()
    }
}
