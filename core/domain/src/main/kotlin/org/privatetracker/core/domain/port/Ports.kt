package org.privatetracker.core.domain.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.DiscoveredServer
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.model.RegistrationResult
import org.privatetracker.core.domain.model.ServerActivity
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.TrackerActivity
import java.security.PrivateKey
import java.time.Duration
import java.time.Instant

/** Runs [run]'s block atomically: all repository writes inside it commit together or not at all. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/**
 * The tracker's view of a remote server. Network and HTTP failures are expected here,
 * so they come back as [Outcome.Failure] instead of exceptions.
 */
interface ServerGateway {
    /**
     * Every call goes over TLS to a server holding the key [pin] names. A server with another key is
     * refused during the handshake, before a byte is sent, as
     * [ServerIdentityMismatch][org.privatetracker.core.common.result.DomainError.ServerIdentityMismatch].
     * With a [challenge], the server also signs it; see [org.privatetracker.core.domain.model.ServerIdentity].
     */
    suspend fun health(serverUrl: String, pin: ServerPin, challenge: String? = null): Outcome<ServerInfo>

    /**
     * Device routes seal their body for [encryptionKey], one the server's identity key vouched for, and
     * read the sealed answer; from 0.5 nothing else travels. A key the server has already dropped comes
     * back as [EncryptionKeyUnknown][org.privatetracker.core.common.result.DomainError.EncryptionKeyUnknown].
     */
    suspend fun register(
        serverUrl: String,
        pin: ServerPin,
        registration: DeviceRegistration,
        encryptionKey: EncryptionKey,
    ): Outcome<RegistrationResult>

    /** With a whole key as [pin], the answer also only counts if the server signed it with that key. */
    suspend fun uploadLocations(
        serverUrl: String,
        pin: ServerPin,
        deviceId: DeviceId,
        locations: List<Location>,
        encryptionKey: EncryptionKey,
    ): Outcome<LocationBatchResult>
}

/**
 * The tracker's signing keys, one per device id, so a new identity always comes with a new key.
 * Private keys never leave the store; on Android they live in the Keystore. Both methods throw
 * [DeviceKeyException] when the store fails.
 */
interface DeviceKeys {
    /** The public key of [deviceId], created on first use: ECDSA P-256, Base64 X.509 SubjectPublicKeyInfo. */
    suspend fun publicKey(deviceId: DeviceId): String

    /** SHA256withECDSA signature of [data], DER encoded. */
    suspend fun sign(deviceId: DeviceId, data: ByteArray): ByteArray
}

class DeviceKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The server's own identity key, ECDSA P-256 like the device keys. Trackers pin it when they pair, so
 * they can tell their server from any other at the same address; from 0.4 it is also the key of the
 * server's TLS certificate. Throws [DeviceKeyException] on failure.
 */
interface ServerKeys {
    suspend fun publicKey(): String
    suspend fun sign(data: ByteArray): ByteArray
}

/**
 * Makes and opens the server's encryption keys (ECDH P-256, see
 * [EncryptionKey][org.privatetracker.core.domain.model.EncryptionKey]). The private half leaves it only
 * protected: on Android, encrypted with a Keystore key of its own that never leaves the phone. Every
 * method throws [DeviceKeyException] when the key store fails.
 */
interface EncryptionKeyVault {
    suspend fun generate(): GeneratedEncryptionKey

    /** The private key that [protectedPrivateKey] holds, ready for key agreement. */
    suspend fun privateKey(protectedPrivateKey: String): PrivateKey

    /** Destroys what protects [protectedPrivateKey], so neither it nor any older copy of it opens again. */
    suspend fun destroy(protectedPrivateKey: String)
}

/** A new key pair: the public key as Base64 X.509, the private key as only its vault can read it. */
class GeneratedEncryptionKey(val publicKey: String, val protectedPrivateKey: String)

/** Server-side checks of the keys and signatures trackers send. */
interface SignatureVerifier {
    /** True when [publicKey] is a well-formed key of the algorithm the protocol uses. */
    fun isValidKey(publicKey: String): Boolean

    fun verify(publicKey: String, data: ByteArray, signature: ByteArray): Boolean
}

/** Nonces of recently accepted requests, so a captured request cannot be sent again. */
fun interface NonceRegistry {
    /** Remembers [nonce] of [deviceId] until [expiresAt]. False when it was already seen: a replay. */
    fun register(deviceId: DeviceId, nonce: String, expiresAt: Instant): Boolean
}

/** Finds PrivateTracker servers announcing themselves on the local network. */
fun interface ServerDiscovery {
    /** Listens for up to [timeout] and returns what answered; empty when discovery is unavailable. */
    suspend fun discover(timeout: Duration): List<DiscoveredServer>
}

data class LocationRequestSpec(
    val intervalMillis: Long,
    val minDistanceM: Float,
    val priority: LocationPriority,
)

/** Stream of fixes from the platform location service while collected. */
interface LocationSource {
    fun locations(request: LocationRequestSpec): Flow<LocationFix>
}

/**
 * The phone's significant-motion sensor, from 0.6 on: low power, it wakes the tracker when the phone
 * starts moving, so fixes return to the normal rate without waiting for the next slow fix.
 */
fun interface MotionSensor {
    /** One element each time the phone starts moving, while collected; ends at once when the phone has no such sensor. */
    fun motions(): Flow<Unit>
}

fun interface BatteryLevelProvider {
    /** Battery level from 0 to 100, or null when unknown. */
    fun currentLevelPct(): Int?
}

interface PermissionChecker {
    /** False when this Android version has no such permission; it then needs no grant. */
    fun isApplicable(permission: AppPermission): Boolean
    fun isGranted(permission: AppPermission): Boolean
}

/**
 * Starts and stops the tracking service. Android allows the start only while the app is visible
 * or right after boot; [activity] is what the running service reports.
 */
interface TrackingController {
    val activity: StateFlow<TrackerActivity>
    fun start()
    fun stop()
}

/** Starts and stops the server service, under the same restrictions as [TrackingController]. */
interface ServerController {
    val activity: StateFlow<ServerActivity>
    fun start()
    fun stop()
}

/** Uploads that must happen even if the app dies, once the network allows. */
fun interface UploadScheduler {
    fun scheduleUpload(delay: Duration)
}

interface NetworkInfoProvider {
    /** The addresses of the interfaces that are up, again whenever the networks change. */
    fun observeAddresses(): Flow<List<NetworkAddress>>
}
