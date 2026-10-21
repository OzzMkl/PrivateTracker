package org.privatetracker.core.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Another server this one is paired with, from 1.0 on. Paired servers share their devices and
 * locations both ways, each ending up with what both have; geofences, web accounts and keys stay each
 * server's own. Each pins the other's identity key, as a tracker pins its server's.
 */
data class Peer(
    /** Its identity key, Base64 X.509 SubjectPublicKeyInfo: who it is, whatever its addresses. */
    val publicKey: String,
    val name: String,
    /** Where it said it can be reached, in the order to try them. */
    val addresses: List<String>,
    val pairedAt: Instant,
    val sync: PeerSync = PeerSync(),
) {
    /** The 16 hex digits both servers show, as for trackers. */
    val fingerprint: String get() = keyFingerprint(publicKey) ?: "?"

    /** How it names itself in the requests it signs: its fingerprint without dashes, lower case. */
    val label: String get() = peerLabel(publicKey)
}

fun peerLabel(publicKey: String): String = keyFingerprint(publicKey)?.replace("-", "")?.lowercase() ?: ""

/**
 * How far two servers got: [received] is how much of the peer's data this server has, [sent] how much
 * of this server's data the peer last said it has. Both count in the numbering of the server the data
 * comes from, which only grows.
 */
data class PeerSync(
    /** The last exchange that went through, whichever server started it. */
    val lastExchangeAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    /** [org.privatetracker.core.common.result.DomainError.code] of the last attempt this server made, if it failed. */
    val lastError: String? = null,
    val received: SyncCursor = SyncCursor.START,
    val sent: SyncCursor = SyncCursor.START,
)

/**
 * A position in a server's own numbering: of its locations, which only grows, and of its changes to
 * devices (new, edited, removed), which only grows too.
 */
data class SyncCursor(val locations: Long, val devices: Long) {
    companion object {
        /** Nothing yet: device changes start below zero, so devices from before 1.0 count too. */
        val START = SyncCursor(locations = 0, devices = -1)
    }
}

/**
 * One message of an exchange between paired servers, either way: what the sender has of the other
 * ([have]), what it sends of its own up to [upTo], and whether more is waiting.
 */
data class SyncBatch(
    val serverName: String,
    val addresses: List<String>,
    val have: SyncCursor,
    val upTo: SyncCursor,
    /** Devices changed since the other's cursor and those the locations belong to, with [Device.updatedAt]. */
    val devices: List<Device>,
    val removedDevices: List<RemovedDevice>,
    /** With [Location.deviceId] and the [Location.receivedAt] of the server that first got them. */
    val locations: List<Location>,
    val more: Boolean,
    /**
     * The sender found the other side claiming more of its data than it ever numbered: it was restored
     * from a backup. It sends everything again from the start, and the other side takes [upTo] as is.
     */
    val reset: Boolean = false,
)

/** A device the owner removed on some server; every paired server removes it too, unless it came back since. */
data class RemovedDevice(val id: DeviceId, val removedAt: Instant)

/** A one-time ticket for an invite to pair another server. [usedBy] is the key of the server that used it. */
data class PeerTicket(
    val id: String,
    val secret: String,
    val expiresAt: Instant,
    val usedBy: String? = null,
)

/** What a server sends to join the one whose invite it read. */
data class PeerJoin(
    val ticketId: String,
    val serverName: String,
    val publicKey: String,
    val addresses: List<String>,
    val proof: String,
)

/** The inviting server's answer, so the joining one can name it and reach it. */
data class PeerWelcome(val serverName: String, val addresses: List<String>)

/**
 * HMAC-SHA256 over the joining server's key, keyed with the invite's secret: the secret never travels,
 * and a proof seen on the network is worth nothing for another key.
 */
fun peerPairingProof(secret: String, publicKey: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal("PrivateTracker-peer-pairing-v1\n$publicKey".encodeToByteArray()))
}

fun isValidPeerPairingProof(secret: String, publicKey: String, proof: String): Boolean =
    runCatching { MessageDigest.isEqual(peerPairingProof(secret, publicKey).encodeToByteArray(), proof.encodeToByteArray()) }
        .getOrDefault(false)

/** A request one server signed for another, as the API received it. */
class SignedPeerRequest(
    /** The [Peer.label] it claims to come from. */
    val label: String,
    val signedAt: Instant,
    val nonce: String,
    val signingInput: ByteArray,
    val signature: ByteArray,
)
