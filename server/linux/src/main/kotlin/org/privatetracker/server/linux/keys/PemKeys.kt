package org.privatetracker.server.linux.keys

import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.server.hostkeys.Pkcs12
import org.privatetracker.server.hostkeys.guarded
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** The owner's own certificate for the web app: a chain and its key, as `tailscale cert` or certbot write them. */
data class PemFiles(val certificate: Path, val key: Path)

/** The web app's PEM files cannot be used; the message says why. */
class WebCertificateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads PEM certificates and private keys without BouncyCastle. Keys may be PKCS#8 (`PRIVATE KEY`),
 * SEC1 (`EC PRIVATE KEY`) or PKCS#1 (`RSA PRIVATE KEY`); the last two are wrapped into PKCS#8, which
 * is all the JDK reads. Encrypted keys are refused: the server would need their password at every start.
 */
object Pem {
    fun certificates(text: String): List<X509Certificate> {
        val blocks = blocks(text).filter { it.first == "CERTIFICATE" }
        if (blocks.isEmpty()) throw DeviceKeyException("No CERTIFICATE block")
        val factory = CertificateFactory.getInstance("X.509")
        return blocks.map { factory.generateCertificate(it.second.inputStream()) as X509Certificate }
    }

    fun privateKey(text: String): PrivateKey {
        val (type, der) = blocks(text).firstOrNull { it.first.endsWith("PRIVATE KEY") } ?: throw DeviceKeyException("No PRIVATE KEY block")
        return when (type) {
            "PRIVATE KEY" -> pkcs8(der)
            "EC PRIVATE KEY" -> KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(sec1ToPkcs8(der)))
            "RSA PRIVATE KEY" -> KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)))
            "ENCRYPTED PRIVATE KEY" -> throw DeviceKeyException("The key is encrypted; save it without a password (chmod 600 protects it)")
            else -> throw DeviceKeyException("Unsupported key type $type")
        }
    }

    /** The PEM blocks of [text], as their label and decoded bytes. */
    private fun blocks(text: String): List<Pair<String, ByteArray>> =
        Regex("-----BEGIN ([A-Z0-9 ]+)-----([^-]*)-----END \\1-----").findAll(text).map { match ->
            val body = match.groupValues[2].lines().filterNot { ':' in it }.joinToString("").filterNot(Char::isWhitespace)
            match.groupValues[1] to Base64.getDecoder().decode(body)
        }.toList()

    private fun pkcs8(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in listOf("EC", "RSA", "Ed25519")) {
            runCatching { return KeyFactory.getInstance(algorithm).generatePrivate(spec) }
        }
        throw DeviceKeyException("The PRIVATE KEY is not an EC, RSA or Ed25519 key")
    }

    /** ECPrivateKey (RFC 5915) into PrivateKeyInfo, with the curve the key names in its [0] field. */
    private fun sec1ToPkcs8(sec1: ByteArray): ByteArray {
        val fields = Der.children(Der.read(sec1, 0))
        val curve = fields.firstOrNull { it.tag == 0xa0 }?.let { Der.children(it).single() }
            ?: throw DeviceKeyException("The EC PRIVATE KEY does not name its curve")
        val algorithm = Der.sequence(Der.encode(0x06, Der.oid(OID_EC_PUBLIC_KEY)), curve.encoded)
        return Der.sequence(Der.encode(0x02, byteArrayOf(0)), algorithm, Der.encode(0x04, sec1))
    }

    /** RSAPrivateKey (RFC 8017) into PrivateKeyInfo. */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val algorithm = Der.sequence(Der.encode(0x06, Der.oid(OID_RSA_ENCRYPTION)), Der.encode(0x05, ByteArray(0)))
        return Der.sequence(Der.encode(0x02, byteArrayOf(0)), algorithm, Der.encode(0x04, pkcs1))
    }

    private const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    private const val OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
}

/** Just enough DER to take a key apart and wrap it again. */
internal object Der {
    class Element(val tag: Int, val value: ByteArray, val encoded: ByteArray)

    fun read(bytes: ByteArray, offset: Int): Element {
        var position = offset
        val tag = bytes[position++].toInt() and 0xff
        var length = bytes[position++].toInt() and 0xff
        if (length and 0x80 != 0) {
            val count = length and 0x7f
            if (count !in 1..3) throw DeviceKeyException("Unsupported DER length")
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[position++].toInt() and 0xff) }
        }
        if (position + length > bytes.size) throw DeviceKeyException("Truncated DER")
        return Element(tag, bytes.copyOfRange(position, position + length), bytes.copyOfRange(offset, position + length))
    }

    fun children(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        var position = 0
        while (position < parent.value.size) {
            val child = read(parent.value, position)
            result += child
            position += child.encoded.size
        }
        return result
    }

    fun sequence(vararg parts: ByteArray) = encode(0x30, *parts)

    fun encode(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            body.size < 0x80 -> out.write(body.size)
            body.size < 0x100 -> { out.write(0x81); out.write(body.size) }
            body.size < 0x10000 -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xff) }
            else -> { out.write(0x83); out.write(body.size shr 16); out.write((body.size shr 8) and 0xff); out.write(body.size and 0xff) }
        }
        out.write(body)
        return out.toByteArray()
    }

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        arcs.drop(2).forEach { arc ->
            val groups = generateSequence(arc) { (it shr 7).takeIf { rest -> rest > 0 } }.map { (it and 0x7f).toInt() }.toList().reversed()
            groups.forEachIndexed { i, group -> out.write(if (i < groups.lastIndex) group or 0x80 else group) }
        }
        return out.toByteArray()
    }
}

/** A chain and its key, loaded and checked to belong together. */
class WebKeyPair(val chain: List<X509Certificate>, val key: PrivateKey) {
    init {
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val algorithm = when (key.algorithm) {
            "EC" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            else -> key.algorithm
        }
        val signature = Signature.getInstance(algorithm).apply { initSign(key); update(probe) }.sign()
        val matches = Signature.getInstance(algorithm).apply { initVerify(chain.first().publicKey); update(probe) }.verify(signature)
        if (!matches) throw DeviceKeyException("The key does not belong to the first certificate of the file")
    }

    companion object {
        /** Throws [DeviceKeyException] with what is wrong, whatever is wrong with the files. */
        fun load(files: PemFiles): WebKeyPair = try {
            guarded { WebKeyPair(Pem.certificates(Files.readString(files.certificate)), Pem.privateKey(Files.readString(files.key))) }
        } catch (e: DeviceKeyException) {
            throw e
        } catch (e: RuntimeException) {
            // Bad Base64, truncated DER and the like.
            throw DeviceKeyException("Unreadable PEM: ${e.message}", e)
        }
    }
}

/** What the server page shows about a certificate. */
fun X509Certificate.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }

fun X509Certificate.commonName(): String =
    subjectX500Principal.name.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=")
        ?: subjectX500Principal.name

/**
 * The web port's key manager for the owner's PEM files. Certificates from Let's Encrypt or `tailscale
 * cert` last 90 days and are renewed in place, so the files are looked at again, at most once a
 * [checkEvery], and a new pair is used from the next connection on; one that does not load is logged
 * and the old pair stays. Aliases carry the pair's generation, so a handshake that began with one
 * pair finishes with it even if the files changed meanwhile.
 */
class ReloadingKeyManager(
    private val files: PemFiles,
    private val clock: Clock = Clock.systemUTC(),
    private val checkEvery: Duration = Duration.ofMinutes(1),
) : X509ExtendedKeyManager() {
    private class Loaded(val generation: Int, val pair: WebKeyPair, val modified: Pair<Instant, Instant>, val manager: X509ExtendedKeyManager)

    private val log = LoggerFactory.getLogger("WebCertificate")
    private val generations = AtomicInteger()

    @Volatile private var current: Loaded = try {
        load(modified())
    } catch (e: Exception) {
        throw WebCertificateException(e.message ?: e.javaClass.simpleName, e)
    }
    @Volatile private var previous: Loaded? = null
    @Volatile private var checkedAt: Instant = clock.instant()

    /** The pair in use now, for the server page. */
    val pair: WebKeyPair get() = current.pair

    private fun modified(): Pair<Instant, Instant> =
        Files.getLastModifiedTime(files.certificate).toInstant() to Files.getLastModifiedTime(files.key).toInstant()

    private fun load(modified: Pair<Instant, Instant>): Loaded {
        val pair = WebKeyPair.load(files)
        val generation = generations.incrementAndGet()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("web-$generation", pair.key, Pkcs12.PASSWORD, pair.chain.toTypedArray())
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, Pkcs12.PASSWORD) }
        return Loaded(generation, pair, modified, factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().single())
    }

    private fun refresh() {
        val now = clock.instant()
        if (Duration.between(checkedAt, now) < checkEvery) return
        synchronized(this) {
            if (Duration.between(checkedAt, now) < checkEvery) return
            checkedAt = now
            try {
                val modified = modified()
                if (modified == current.modified) return
                val fresh = load(modified)
                previous = current
                current = fresh
                log.info("Loaded the renewed web certificate {} (valid until {})", fresh.pair.chain.first().commonName(), fresh.pair.chain.first().notAfter.toInstant())
            } catch (e: Exception) {
                log.warn("The web certificate files changed but do not load; still using the previous ones: {}", e.message)
            }
        }
    }

    private fun byAlias(alias: String?): X509ExtendedKeyManager? {
        // SunX509 hands out "web-3"; PKIX, if the JDK is set to it, "0.0.web-3".
        val generation = alias?.let { ALIAS.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return listOfNotNull(current, previous).firstOrNull { it.generation == generation }?.manager
    }

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? {
        refresh()
        return current.manager.chooseEngineServerAlias(keyType, issuers, engine)
    }

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
        refresh()
        return current.manager.chooseServerAlias(keyType, issuers, socket)
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = byAlias(alias)?.getCertificateChain(alias)
    override fun getPrivateKey(alias: String?): PrivateKey? = byAlias(alias)?.getPrivateKey(alias)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = current.manager.getServerAliases(keyType, issuers)

    // A server only: it never presents a client certificate.
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    private companion object {
        val ALIAS = Regex("web-(\\d+)$")
    }
}
