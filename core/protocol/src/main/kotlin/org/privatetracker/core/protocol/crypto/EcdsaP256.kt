package org.privatetracker.core.protocol.crypto

import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.port.SignatureVerifier
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The one signature algorithm of protocol v1: ECDSA on NIST P-256 with SHA-256. Android's Keystore
 * holds this kind of key on every supported version. Public keys travel as Base64 X.509
 * SubjectPublicKeyInfo, which any JCA provider reads. Plain JCA, so it runs on Android and on a JVM.
 */
object EcdsaP256 : SignatureVerifier {
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val KEY_ALGORITHM = "EC"
    const val CURVE = "secp256r1"

    /** Order of the P-256 base point; a key on any other curve has another. */
    private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()

    fun encode(publicKey: PublicKey): String = Base64.getEncoder().encodeToString(publicKey.encoded)

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    override fun isValidKey(publicKey: String): Boolean = decode(publicKey) != null

    override fun verify(publicKey: String, data: ByteArray, signature: ByteArray): Boolean {
        val key = decode(publicKey) ?: return false
        // A malformed signature throws instead of returning false.
        return runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    private fun decode(publicKey: String): ECPublicKey? = runCatching {
        val der = Base64.getDecoder().decode(publicKey)
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
    }.getOrNull()?.takeIf { it.params.order == P256_ORDER }
}

/** Keys held in memory, for trackers that run on a JVM such as the simulator, and for tests. */
class InMemoryDeviceKeys : DeviceKeys {
    private val pairs = ConcurrentHashMap<DeviceId, KeyPair>()

    override suspend fun publicKey(deviceId: DeviceId): String = EcdsaP256.encode(pair(deviceId).public)

    override suspend fun sign(deviceId: DeviceId, data: ByteArray): ByteArray = EcdsaP256.sign(pair(deviceId).private, data)

    private fun pair(deviceId: DeviceId): KeyPair = pairs.computeIfAbsent(deviceId) { EcdsaP256.generateKeyPair() }
}

/** A server key held in memory, for servers on a JVM and for tests. */
class InMemoryServerKeys : ServerKeys {
    private val pair: KeyPair by lazy { EcdsaP256.generateKeyPair() }

    override suspend fun publicKey(): String = EcdsaP256.encode(pair.public)

    override suspend fun sign(data: ByteArray): ByteArray = EcdsaP256.sign(pair.private, data)
}
