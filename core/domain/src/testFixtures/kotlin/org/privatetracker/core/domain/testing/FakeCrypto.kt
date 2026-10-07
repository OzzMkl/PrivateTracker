package org.privatetracker.core.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.PairingTicket
import org.privatetracker.core.domain.model.SignedRequest
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.EncryptionKeyVault
import org.privatetracker.core.domain.port.GeneratedEncryptionKey
import org.privatetracker.core.domain.port.NonceRegistry
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.repository.PairingTicketStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.time.Instant
import java.util.Base64

// Deterministic stand-ins for the signature machinery: a "signature" is the SHA-256 of key and data.
// Real ECDSA is tested in core:protocol; these keep domain tests about rules, not cryptography.

private const val FAKE_KEY_PREFIX = "fake-key"

/** [generation] gives the same device another key, as after losing the original one. */
fun fakePublicKey(deviceId: DeviceId, generation: Int = 1): String =
    Base64.getEncoder().encodeToString("$FAKE_KEY_PREFIX-$generation:${deviceId.value}".encodeToByteArray())

fun fakeSignature(publicKey: String, data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encodeToByteArray() + data)

/** [failing] makes it behave like a broken Keystore. */
class FakeDeviceKeys(var generation: Int = 1, var failing: Boolean = false) : DeviceKeys {
    override suspend fun publicKey(deviceId: DeviceId): String {
        if (failing) throw DeviceKeyException("Keystore unavailable")
        return fakePublicKey(deviceId, generation)
    }

    override suspend fun sign(deviceId: DeviceId, data: ByteArray): ByteArray = fakeSignature(publicKey(deviceId), data)
}

object FakeSignatureVerifier : SignatureVerifier {
    override fun isValidKey(publicKey: String): Boolean =
        runCatching { Base64.getDecoder().decode(publicKey).decodeToString() }.getOrNull()?.startsWith(FAKE_KEY_PREFIX) == true

    override fun verify(publicKey: String, data: ByteArray, signature: ByteArray): Boolean =
        fakeSignature(publicKey, data).contentEquals(signature)
}

/** Remembers every nonce forever; tests never run long enough for expiry to matter. */
class InMemoryNonces : NonceRegistry {
    private val seen = mutableSetOf<Pair<DeviceId, String>>()
    override fun register(deviceId: DeviceId, nonce: String, expiresAt: Instant): Boolean = seen.add(deviceId to nonce)
}

/** A request from [deviceId] signed with [publicKey] at [signedAt]. */
fun aSignedRequest(
    deviceId: DeviceId = DEVICE_A,
    publicKey: String = fakePublicKey(deviceId),
    signedAt: Instant = T0,
    nonce: String = "nonce-1",
    input: ByteArray = "POST /api/v1/devices/register".encodeToByteArray(),
): SignedRequest = SignedRequest(deviceId, signedAt, nonce, input, fakeSignature(publicKey, input))

/** The server key in [fakePublicKey]'s style, signing as [FakeSignatureVerifier] expects. */
class FakeServerKeys(private val key: String = SERVER_KEY) : ServerKeys {
    override suspend fun publicKey(): String = key
    override suspend fun sign(data: ByteArray): ByteArray = fakeSignature(key, data)

    companion object {
        val SERVER_KEY: String = Base64.getEncoder().encodeToString("$FAKE_KEY_PREFIX-server".encodeToByteArray())
    }
}

/** One open ticket at a time, like the server's store. */
class InMemoryPairingTickets : PairingTicketStore {
    private val current = MutableStateFlow<PairingTicket?>(null)

    override fun issue(ticket: PairingTicket) {
        current.value = ticket
    }

    override fun get(id: String): PairingTicket? = current.value?.takeIf { it.id == id }

    override fun consume(id: String, deviceId: DeviceId): Boolean {
        var consumed = false
        current.update { ticket ->
            consumed = ticket?.id == id && (ticket.usedBy == null || ticket.usedBy == deviceId)
            if (consumed) ticket?.copy(usedBy = deviceId) else ticket
        }
        return consumed
    }

    override fun withdraw(id: String) {
        current.update { if (it?.id == id) null else it }
    }

    override fun observe(id: String): Flow<PairingTicket?> = current.map { it?.takeIf { ticket -> ticket.id == id } }
}

/**
 * Encryption keys as plain labels: key N is "fake-encryption-N", and its private half a [PrivateKey]
 * that only carries the label. A destroyed key no longer opens. Real HPKE is tested in core:protocol
 * and core:network.
 */
class FakeEncryptionKeyVault(var failing: Boolean = false) : EncryptionKeyVault {
    var generated = 0
        private set

    /** The protected private keys opened so far, in order. */
    val opened = mutableListOf<String>()

    /** The protected private keys destroyed so far, in order. */
    val destroyed = mutableListOf<String>()

    /** Makes only opening fail, as when the key store lost what protects the stored keys. */
    var failingToOpen = false

    override suspend fun generate(): GeneratedEncryptionKey {
        if (failing) throw DeviceKeyException("Keystore unavailable")
        generated++
        return GeneratedEncryptionKey(fakeEncryptionKey(generated), "protected-$generated")
    }

    override suspend fun privateKey(protectedPrivateKey: String): PrivateKey {
        if (failing || failingToOpen || protectedPrivateKey in destroyed) throw DeviceKeyException("Keystore unavailable")
        opened += protectedPrivateKey
        return LabelPrivateKey(protectedPrivateKey)
    }

    override suspend fun destroy(protectedPrivateKey: String) {
        if (failing) throw DeviceKeyException("Keystore unavailable")
        destroyed += protectedPrivateKey
    }

    private class LabelPrivateKey(val label: String) : PrivateKey {
        override fun getAlgorithm(): String = "EC"
        override fun getFormat(): String = "label"
        override fun getEncoded(): ByteArray = label.encodeToByteArray()
    }
}

fun fakeEncryptionKey(number: Int): String = Base64.getEncoder().encodeToString("fake-encryption-$number".encodeToByteArray())
