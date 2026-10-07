package org.privatetracker.core.domain.model

import java.time.Duration
import java.time.Instant

/**
 * A key trackers encrypt their requests to, from 0.5 on: ECDH P-256, Base64 X.509 SubjectPublicKeyInfo.
 * Trackers use it until [useUntil]; the server keeps its private half a little longer, see [EncryptionKeyPolicy].
 * Its [id] is the key's fingerprint.
 */
data class EncryptionKey(val id: String, val publicKey: String, val useUntil: Instant)

/** [key] with the server identity key's signature over [encryptionKeyInput], so trackers know it is their server's. */
data class SignedEncryptionKey(val key: EncryptionKey, val signature: String)

/** What the server's identity key signs to vouch for an encryption key. */
fun encryptionKeyInput(key: EncryptionKey): ByteArray =
    "PrivateTracker-encryption-key-v1\n${key.id}\n${key.publicKey}\n${key.useUntil.toEpochMilli()}".encodeToByteArray()

/** One of the server's encryption keys as stored; only the platform's key store can open [protectedPrivateKey]. */
data class StoredEncryptionKey(
    val key: EncryptionKey,
    val createdAt: Instant,
    val protectedPrivateKey: String,
)

/** The tracker's copy of its server's encryption key, and the identity key that vouched for it. */
data class TrustedEncryptionKey(val key: EncryptionKey, val serverKey: String)

/**
 * How long the server's encryption keys live. A key is offered to trackers for a week; the server
 * offers a new one once less than [renewBefore] is left, so a tracker never receives a key about to
 * end, whatever its clock. Afterwards the private half is kept for [keepAfter], for requests sealed just
 * before the end, and then deleted: traffic captured on the way can no longer be opened.
 */
data class EncryptionKeyPolicy(
    val lifetime: Duration = Duration.ofDays(7),
    val renewBefore: Duration = Duration.ofHours(1),
    val keepAfter: Duration = Duration.ofDays(1),
)
