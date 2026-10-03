package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.PAIRING_SECRET_BYTES
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.PairingTicket
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.ServerConfigRepository
import java.security.SecureRandom
import java.time.Duration
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

/** The fingerprint of the server's key, which a paired tracker shows too; null when the key store fails. */
class GetServerKeyFingerprint(private val serverKeys: ServerKeys) {
    suspend operator fun invoke(): String? = try {
        keyFingerprint(serverKeys.publicKey())
    } catch (e: DeviceKeyException) {
        null
    }
}
