package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.EncryptionKeyPolicy
import org.privatetracker.core.domain.model.SignedEncryptionKey
import org.privatetracker.core.domain.model.StoredEncryptionKey
import org.privatetracker.core.domain.model.encryptionKeyInput
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.EncryptionKeyVault
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.EncryptionKeyRepository
import java.security.PrivateKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The server's encryption keys, from 0.5 on. Trackers seal every request for the [current] key, which
 * the identity key signs, and the server opens it with [decryptionKey]. Keys rotate by themselves as the
 * [policy] says, or at once with [rotate]. A deleted key is also destroyed in the vault, so no copy of
 * it left on storage opens again. Keep one instance per process: it serializes rotations and remembers
 * opened private keys and signatures. The key store's failures surface as [DeviceKeyException].
 */
class EncryptionKeyRing(
    private val keys: EncryptionKeyRepository,
    private val vault: EncryptionKeyVault,
    private val serverKeys: ServerKeys,
    private val clock: Clock,
    private val policy: EncryptionKeyPolicy = EncryptionKeyPolicy(),
) {
    private val mutex = Mutex()
    private val signatures = ConcurrentHashMap<String, String>()
    private val opened = ConcurrentHashMap<String, DecryptionKey>()

    /** The key to hand trackers, signed. Makes a new one when the newest is about to end, and deletes keys past their time. */
    suspend fun current(): SignedEncryptionKey {
        val key = mutex.withLock {
            val now = clock.now()
            val newest = deleteExpired(now).lastOrNull()?.key
            if (newest != null && Duration.between(now, newest.useUntil) > policy.renewBefore) return@withLock newest
            try {
                create(now, replaceAll = false)
            } catch (e: DeviceKeyException) {
                // A key store failing for a moment must not take away a key that still serves.
                newest?.takeIf { now.isBefore(it.useUntil) } ?: throw e
            }
        }
        return sign(key)
    }

    /**
     * Key [id] with its private half, while the server keeps it; null for an id it never had or already
     * deleted. A stored key whose private half no longer opens, as after the key store lost what protects
     * it, is deleted too: trackers are then told to fetch the current key, and [current] makes a new one.
     */
    suspend fun decryptionKey(id: String): DecryptionKey? = mutex.withLock {
        val stored = deleteExpired(clock.now()).firstOrNull { it.key.id == id } ?: return@withLock null
        opened[id]?.let { return@withLock it }
        val privateKey = try {
            vault.privateKey(stored.protectedPrivateKey)
        } catch (e: DeviceKeyException) {
            remove { it.key.id == id }
            return@withLock null
        }
        DecryptionKey(stored.key, privateKey).also { opened[id] = it }
    }

    /** A new key at once. Every older key is deleted with it, so nothing sealed for them opens here any more. */
    suspend fun rotate(): EncryptionKey = mutex.withLock { create(clock.now(), replaceAll = true) }

    /** The newest key, as trackers get it; null until the first one is made. */
    fun observeNewest(): Flow<EncryptionKey?> = keys.observe().map { it.lastOrNull()?.key }

    private suspend fun create(now: Instant, replaceAll: Boolean): EncryptionKey {
        val generated = vault.generate()
        val id = checkNotNull(keyFingerprint(generated.publicKey)) { "The vault returned a key that is not Base64" }
        val stored = StoredEncryptionKey(EncryptionKey(id, generated.publicKey, now.plus(policy.lifetime)), now, generated.protectedPrivateKey)
        if (replaceAll) remove { true }
        val kept = keys.update { current -> current + stored }
        forgetAllBut(kept)
        return stored.key
    }

    private suspend fun deleteExpired(now: Instant): List<StoredEncryptionKey> = remove { !isKept(it, now) }

    /** Deletes the keys [drop] picks, then destroys them in the vault. What is left, oldest first. */
    private suspend fun remove(drop: (StoredEncryptionKey) -> Boolean): List<StoredEncryptionKey> {
        var removed = emptyList<StoredEncryptionKey>()
        val kept = keys.update { all ->
            val (gone, stay) = all.partition(drop)
            removed = gone
            stay
        }
        forgetAllBut(kept)
        removed.forEach { stored ->
            try {
                vault.destroy(stored.protectedPrivateKey)
            } catch (e: DeviceKeyException) {
                // Already forgotten here; at worst its protection lingers in the key store.
            }
        }
        return kept
    }

    private fun forgetAllBut(kept: List<StoredEncryptionKey>) {
        val ids = kept.map { it.key.id }.toSet()
        opened.keys.retainAll(ids)
        signatures.keys.retainAll(ids)
    }

    private fun isKept(stored: StoredEncryptionKey, now: Instant): Boolean = now.isBefore(stored.key.useUntil.plus(policy.keepAfter))

    private suspend fun sign(key: EncryptionKey): SignedEncryptionKey {
        val signature = signatures[key.id]
            ?: Base64.getEncoder().encodeToString(serverKeys.sign(encryptionKeyInput(key))).also { signatures[key.id] = it }
        return SignedEncryptionKey(key, signature)
    }
}

/** An encryption key the server still keeps, ready to open what trackers sealed for it. */
class DecryptionKey(val key: EncryptionKey, val privateKey: PrivateKey)

/** The key trackers seal for now, for the server screen. Makes the first one, or the next one when due. */
class ObserveEncryptionKey(private val ring: EncryptionKeyRing) {
    operator fun invoke(): Flow<EncryptionKey?> = flow {
        try {
            ring.current()
        } catch (e: DeviceKeyException) {
            // The screen then shows what is stored, or nothing.
        }
        emitAll(ring.observeNewest())
    }
}

/** The owner's "rotate now": a new key, and the old ones deleted. Trackers fetch the new one by themselves. */
class RotateEncryptionKey(private val ring: EncryptionKeyRing) {
    suspend operator fun invoke(): Outcome<EncryptionKey> = try {
        ring.rotate().asSuccess()
    } catch (e: DeviceKeyException) {
        DomainError.DeviceKeyUnavailable.asFailure()
    }
}
