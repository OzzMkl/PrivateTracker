package org.privatetracker.core.protocol.crypto

import java.math.BigInteger
import org.privatetracker.core.domain.port.EncryptionKeyVault
import org.privatetracker.core.domain.port.GeneratedEncryptionKey
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid Public Key Encryption (RFC 9180) in base mode, with the one suite protocol v1 uses from 0.5 on:
 * DHKEM(P-256, HKDF-SHA256), HKDF-SHA256 and AES-128-GCM. X25519 would be the usual curve, but Android
 * only offers it from API 33; ECDH on P-256 exists on every supported version. Plain JCA, so it runs on
 * Android and on a JVM. Checked byte for byte against the RFC's test vectors (appendix A.3.1).
 *
 * A [Sender] or [Recipient] is one HPKE context: not thread-safe, and meant for a single exchange.
 */
object Hpke {
    /** Length of the encapsulated key that travels with the ciphertext: an uncompressed P-256 point. */
    const val ENC_LENGTH = 65

    private const val KEM_ID = 0x0010
    private const val KDF_ID = 0x0001
    private const val AEAD_ID = 0x0001
    private const val MODE_BASE: Byte = 0
    private const val KEY_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val HASH_LENGTH = 32
    private const val TAG_BITS = 128

    private val VERSION_LABEL = "HPKE-v1".encodeToByteArray()
    private val KEM_SUITE = "KEM".encodeToByteArray() + i2osp(KEM_ID, 2)
    private val HPKE_SUITE = "HPKE".encodeToByteArray() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)
    private val EMPTY = ByteArray(0)

    /** The sending side. [enc] travels to the recipient together with what [seal] returns. */
    class Sender internal constructor(val enc: ByteArray, private val context: Context) {
        fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray = context.seal(aad, plaintext)
        fun export(exporterContext: ByteArray, length: Int): ByteArray = context.export(exporterContext, length)
    }

    /** The receiving side, holding the same keys as the [Sender] that made [Sender.enc]. */
    class Recipient internal constructor(private val context: Context) {
        /** Null when the ciphertext or its [aad] was altered, or when it was sealed for another key. */
        fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray? = context.open(aad, ciphertext)
        fun export(exporterContext: ByteArray, length: Int): ByteArray = context.export(exporterContext, length)
    }

    fun setupSender(recipientKey: ECPublicKey, info: ByteArray): Sender = setupSender(recipientKey, info, P256.generateKeyPair())

    /** With a given ephemeral key, which only the RFC's test vectors need. */
    internal fun setupSender(recipientKey: ECPublicKey, info: ByteArray, ephemeral: KeyPair): Sender {
        val enc = P256.encodePoint(ephemeral.public as ECPublicKey)
        val dh = P256.agree(ephemeral.private, recipientKey)
        val sharedSecret = extractAndExpand(dh, enc + P256.encodePoint(recipientKey))
        return Sender(enc, keySchedule(sharedSecret, info))
    }

    /**
     * [recipientPublicKey] must be the public half of [recipientKey]; the KEM binds both. Null when [enc]
     * is not a point of P-256, which also stops invalid-curve attacks on the recipient's static key.
     */
    fun setupRecipient(enc: ByteArray, recipientKey: PrivateKey, recipientPublicKey: ECPublicKey, info: ByteArray): Recipient? {
        val ephemeral = P256.decodePoint(enc) ?: return null
        val dh = try {
            P256.agree(recipientKey, ephemeral)
        } catch (e: GeneralSecurityException) {
            return null
        }
        val sharedSecret = extractAndExpand(dh, enc + P256.encodePoint(recipientPublicKey))
        return Recipient(keySchedule(sharedSecret, info))
    }

    // RFC 9180, section 4.1: DHKEM.

    private fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val prk = labeledExtract(KEM_SUITE, EMPTY, "eae_prk", dh)
        return labeledExpand(KEM_SUITE, prk, "shared_secret", kemContext, HASH_LENGTH)
    }

    // RFC 9180, section 5.1: the key schedule, base mode (no PSK).

    private fun keySchedule(sharedSecret: ByteArray, info: ByteArray): Context {
        val pskIdHash = labeledExtract(HPKE_SUITE, EMPTY, "psk_id_hash", EMPTY)
        val infoHash = labeledExtract(HPKE_SUITE, EMPTY, "info_hash", info)
        val context = byteArrayOf(MODE_BASE) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", EMPTY)
        return Context(
            key = labeledExpand(HPKE_SUITE, secret, "key", context, KEY_LENGTH),
            baseNonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", context, NONCE_LENGTH),
            exporterSecret = labeledExpand(HPKE_SUITE, secret, "exp", context, HASH_LENGTH),
        )
    }

    internal class Context(private val key: ByteArray, private val baseNonce: ByteArray, private val exporterSecret: ByteArray) {
        private var sequence = 0L

        fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray = aead(Cipher.ENCRYPT_MODE, aad, plaintext).also { sequence++ }

        fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray? = try {
            aead(Cipher.DECRYPT_MODE, aad, ciphertext).also { sequence++ }
        } catch (e: GeneralSecurityException) {
            null
        }

        fun export(exporterContext: ByteArray, length: Int): ByteArray =
            labeledExpand(HPKE_SUITE, exporterSecret, "sec", exporterContext, length)

        private fun aead(mode: Int, aad: ByteArray, input: ByteArray): ByteArray {
            check(sequence >= 0) { "Message limit reached" }
            val nonce = baseNonce.copyOf()
            val counter = i2osp(sequence, NONCE_LENGTH)
            for (i in nonce.indices) nonce[i] = (nonce[i].toInt() xor counter[i].toInt()).toByte()
            return aesGcm(mode, key, nonce, aad, input)
        }
    }

    // RFC 9180, section 4: labeled HKDF.

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        extract(salt, VERSION_LABEL + suite + label.encodeToByteArray() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray =
        expand(prk, i2osp(length.toLong(), 2) + VERSION_LABEL + suite + label.encodeToByteArray() + info, length)

    /** HKDF-Extract (RFC 5869). An empty salt is a key of zeros, which HMAC pads to the same thing. */
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * HASH_LENGTH) { "HKDF output too long" }
        val output = ByteArray(length)
        var block = EMPTY
        var filled = 0
        var counter = 1
        while (filled < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - filled)
            block.copyInto(output, filled, 0, take)
            filled += take
            counter++
        }
        return output
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun i2osp(value: Int, length: Int): ByteArray = i2osp(value.toLong(), length)

    private fun i2osp(value: Long, length: Int): ByteArray = ByteArray(length) { i ->
        val shift = 8 * (length - 1 - i)
        if (shift >= Long.SIZE_BITS) 0 else (value ushr shift).toByte()
    }
}

/** AES-GCM with a 128-bit tag, as HPKE and the sealed answers use it. */
internal fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(input)
    }

/**
 * ECDH on NIST P-256 with plain JCA. Public keys travel as Base64 X.509 SubjectPublicKeyInfo, like the
 * signing keys of [EcdsaP256]; HPKE itself works with the uncompressed point (0x04 || X || Y). Every
 * point that comes from outside is checked to lie on the curve before it takes part in an agreement.
 */
object P256 {
    private const val COORDINATE_LENGTH = 32
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    /** The curve's parameters as this JCA provider describes them, taken from a key it made. */
    private val params: ECParameterSpec by lazy { (generateKeyPair().public as ECPublicKey).params }

    fun generateKeyPair(): KeyPair = EcdsaP256.generateKeyPair()

    fun encodePublicKey(key: ECPublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    /** Null unless [publicKey] is Base64 X.509 of a valid P-256 point. */
    fun decodePublicKey(publicKey: String): ECPublicKey? {
        val key = runCatching {
            KeyFactory.getInstance(EcdsaP256.KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        }.getOrNull() as? ECPublicKey ?: return null
        if (key.params.order != ORDER || !isOnCurve(key.w)) return null
        return key
    }

    fun encodePoint(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + unsigned(key.w.affineX) + unsigned(key.w.affineY)

    /** Null unless [bytes] is an uncompressed point on P-256. */
    fun decodePoint(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != 1 + 2 * COORDINATE_LENGTH || bytes[0] != 0x04.toByte()) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_LENGTH))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_LENGTH, bytes.size))
        val point = ECPoint(x, y)
        if (!isOnCurve(point)) return null
        return runCatching {
            KeyFactory.getInstance(EcdsaP256.KEY_ALGORITHM).generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
        }.getOrNull()
    }

    /** A private key from its raw scalar, as the RFC's test vectors give it. */
    internal fun privateKey(scalar: ByteArray): PrivateKey =
        KeyFactory.getInstance(EcdsaP256.KEY_ALGORITHM).generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params))

    /** The x-coordinate of the shared point, always 32 bytes. */
    fun agree(privateKey: PrivateKey, publicKey: ECPublicKey): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
        return if (secret.size >= COORDINATE_LENGTH) secret else ByteArray(COORDINATE_LENGTH - secret.size) + secret
    }

    /** y² = x³ − 3x + b (mod p), with both coordinates in range; the point at infinity never is. */
    private fun isOnCurve(point: ECPoint): Boolean {
        if (point == ECPoint.POINT_INFINITY) return false
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
        val left = y.multiply(y).mod(P)
        val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        return left == right
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == COORDINATE_LENGTH -> bytes
            bytes.size > COORDINATE_LENGTH -> bytes.copyOfRange(bytes.size - COORDINATE_LENGTH, bytes.size)
            else -> ByteArray(COORDINATE_LENGTH - bytes.size) + bytes
        }
    }
}

/**
 * Encryption keys for servers on a JVM and for tests. Nothing protects the private half here: it is
 * just Base64 PKCS#8, so there is nothing to destroy either. On Android the Keystore encrypts it.
 */
class InMemoryEncryptionKeyVault : EncryptionKeyVault {
    override suspend fun generate(): GeneratedEncryptionKey {
        val pair = P256.generateKeyPair()
        return GeneratedEncryptionKey(P256.encodePublicKey(pair.public as ECPublicKey), Base64.getEncoder().encodeToString(pair.private.encoded))
    }

    override suspend fun privateKey(protectedPrivateKey: String): PrivateKey =
        KeyFactory.getInstance(EcdsaP256.KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(protectedPrivateKey)))

    override suspend fun destroy(protectedPrivateKey: String) = Unit
}
