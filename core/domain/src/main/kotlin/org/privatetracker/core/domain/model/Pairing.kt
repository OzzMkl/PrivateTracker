package org.privatetracker.core.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a pairing QR code hands a tracker: the server's name and addresses, the key that identifies
 * it, and a one-time ticket. The ticket's [secret] never travels over the network: the tracker proves
 * it knows it with [pairingProof].
 */
data class PairingInvite(
    val serverName: String,
    val serverUrls: List<String>,
    /** The server's ECDSA P-256 key, Base64 X.509 SubjectPublicKeyInfo; the tracker pins it. */
    val serverKey: String,
    val ticketId: String,
    /** Base64url, [PAIRING_SECRET_BYTES] random bytes. */
    val secret: String,
    val expiresAt: Instant,
)

/** A one-time ticket the server issued for a QR code. [usedBy] is set once a tracker paired with it. */
data class PairingTicket(
    val id: String,
    val secret: String,
    val expiresAt: Instant,
    val usedBy: DeviceId? = null,
)

/** What a registration made from a QR code carries instead of the secret itself. */
data class PairingClaim(val ticketId: String, val proof: String)

/** The server's signature over a tracker's challenge, so the tracker knows which server answered. */
data class ServerIdentity(val publicKey: String, val signature: String)

const val PAIRING_SECRET_BYTES = 16

/**
 * HMAC-SHA256, keyed with the ticket secret, over the device and the key it registers: whoever sees
 * the registration on the network learns neither the secret nor a proof valid for another key.
 */
fun pairingProof(secret: String, deviceId: DeviceId, publicKey: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
    val message = "PrivateTracker-pairing-v1\n${deviceId.value}\n$publicKey".encodeToByteArray()
    return Base64.getEncoder().encodeToString(mac.doFinal(message))
}

/** Constant-time comparison, so timing reveals nothing about a correct proof. */
fun isValidPairingProof(secret: String, deviceId: DeviceId, publicKey: String, proof: String): Boolean =
    runCatching {
        MessageDigest.isEqual(pairingProof(secret, deviceId, publicKey).encodeToByteArray(), proof.encodeToByteArray())
    }.getOrDefault(false)

/** What the server signs to answer a tracker's [challenge]: the challenge and its own time. */
fun serverIdentityInput(challenge: String, serverTime: Instant): ByteArray =
    "PrivateTracker-server-v1\n$challenge\n${serverTime.toEpochMilli()}".encodeToByteArray()

/** A server announcing itself on the local network, as a tracker found it. */
data class DiscoveredServer(val url: String, val keyHint: String?)

/**
 * What a server announces about its key on the network: its fingerprint without dashes. It only spares
 * a tracker from asking every server it finds; the signed health challenge is what proves identity.
 */
fun serverKeyHint(publicKey: String): String? = keyFingerprint(publicKey)?.replace("-", "")
