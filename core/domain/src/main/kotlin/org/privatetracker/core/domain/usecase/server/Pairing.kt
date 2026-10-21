package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.model.PAIRING_SECRET_BYTES
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.PairingTicket
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.advertisedAddresses
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.network.serverAddresses
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.NetworkInfoProvider
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.ServerConfigRepository
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Issues a one-time ticket and the invite its QR code carries. [serverUrls] are the addresses the
 * server screen lists for trackers; a new invite withdraws the previous one if nobody used it.
 */
class CreatePairingInvite(
    private val tickets: PairingTicketStore,
    private val serverKeys: ServerKeys,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val validity: Duration = VALIDITY,
) {
    suspend operator fun invoke(serverUrls: List<String>): Outcome<PairingInvite> {
        if (serverUrls.isEmpty()) return DomainError.Validation("serverUrls", FieldViolation.REQUIRED).asFailure()
        val serverKey = try {
            serverKeys.publicKey()
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        val ticket = PairingTicket(
            id = randomToken(TICKET_ID_BYTES),
            secret = randomToken(PAIRING_SECRET_BYTES),
            expiresAt = clock.now().plus(validity),
        )
        tickets.issue(ticket)
        return PairingInvite(
            serverName = serverConfig.get().serverName,
            serverUrls = serverUrls,
            serverKey = serverKey,
            ticketId = ticket.id,
            secret = ticket.secret,
            expiresAt = ticket.expiresAt,
        ).asSuccess()
    }

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    companion object {
        /** Long enough to walk to the other phone and scan; short enough that a photo of the QR soon expires. */
        val VALIDITY: Duration = Duration.ofMinutes(10)
        private const val TICKET_ID_BYTES = 9
    }
}

/**
 * The device that used a ticket, once one did; null while the QR is still waiting. Follows the device
 * table too: the ticket is taken just before the device is written.
 */
class ObservePairedDevice(
    private val tickets: PairingTicketStore,
    private val devices: DeviceRepository,
) {
    operator fun invoke(ticketId: String): Flow<Device?> =
        combine(tickets.observe(ticketId), devices.observeAllWithLastLocation()) { ticket, rows ->
            ticket?.usedBy?.let { id -> rows.firstOrNull { it.device.id == id }?.device }
        }.distinctUntilChanged()
}

/** Takes the QR's ticket off the table when the code leaves the screen, so a photo of it is worthless. */
class WithdrawPairingInvite(private val tickets: PairingTicketStore) {
    operator fun invoke(ticketId: String) = tickets.withdraw(ticketId)
}

/**
 * Where trackers can reach a phone server now, from 1.0: the addresses of its networks that another
 * phone can use (no loopback), then its relay's name for it. What health signs for paired trackers.
 * Empty while the server is not running.
 */
class GetAdvertisedAddresses(
    private val network: NetworkInfoProvider,
    private val serverConfig: ServerConfigRepository,
    private val controller: ServerController,
    private val getServerKeyFingerprint: GetServerKeyFingerprint,
    private val clock: Clock,
) {
    private val looking = Mutex()
    private var known: Pair<Instant, List<NetworkAddress>>? = null

    suspend operator fun invoke(): List<String> {
        val port = (controller.activity.value.state as? ServerRunState.Running)?.port ?: return emptyList()
        val config = serverConfig.get()
        val local = serverAddresses(networkAddresses(), port, config.bindAddress)
            .filter { it.kind != AddressKind.LOOPBACK }
            .map { it.url }
        return advertisedAddresses(local, config, getServerKeyFingerprint())
    }

    /**
     * Looked up at most once per [LOOK_EVERY]: through a relay anyone may ask for them, and on a phone
     * each look registers a network callback, of which Android allows an app 100 at a time.
     */
    private suspend fun networkAddresses(): List<NetworkAddress> = looking.withLock {
        val now = clock.now()
        known?.takeIf { (at, _) -> Duration.between(at, now) in Duration.ZERO..LOOK_EVERY }?.second
            ?: network.observeAddresses().first().also { known = now to it }
    }

    private companion object {
        val LOOK_EVERY: Duration = Duration.ofSeconds(30)
    }
}

/** The fingerprint of the server's key, which a paired tracker shows too; null when the key store fails. */
class GetServerKeyFingerprint(private val serverKeys: ServerKeys) {
    suspend operator fun invoke(): String? = try {
        keyFingerprint(serverKeys.publicKey())
    } catch (e: DeviceKeyException) {
        null
    }
}
