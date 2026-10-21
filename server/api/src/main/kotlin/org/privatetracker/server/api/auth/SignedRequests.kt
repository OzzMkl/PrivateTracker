package org.privatetracker.server.api.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.PairingTicket
import org.privatetracker.core.domain.model.PeerTicket
import org.privatetracker.core.domain.model.SignedPeerRequest
import org.privatetracker.core.domain.model.SignedRequest
import org.privatetracker.core.domain.port.NonceRegistry
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.PeerTicketStore
import org.privatetracker.core.protocol.v1.PeerSignature
import org.privatetracker.core.protocol.v1.RequestSignature
import org.privatetracker.server.api.plugin.toApiException
import java.time.DateTimeException
import java.time.Instant
import java.util.PriorityQueue

/**
 * The signature this call carries, over the method, path and [body] it actually has. Throws the
 * 401 problem when the header is missing or unreadable; whether it verifies is the domain's call.
 */
internal fun ApplicationCall.signedRequest(body: ByteArray): SignedRequest {
    val header = request.header(HttpHeaders.Authorization) ?: throw unauthenticated(AuthFailure.MISSING)
    val parsed = RequestSignature.parse(header) ?: throw unauthenticated(AuthFailure.MALFORMED)
    val deviceId = DeviceId.parse(parsed.deviceId) ?: throw unauthenticated(AuthFailure.MALFORMED)
    val signedAt = try {
        Instant.ofEpochSecond(parsed.created)
    } catch (e: DateTimeException) {
        throw unauthenticated(AuthFailure.MALFORMED)
    }
    val input = RequestSignature.signingInput(request.httpMethod.value, request.path(), parsed.deviceId, parsed.created, parsed.nonce, body)
    return SignedRequest(deviceId, signedAt, parsed.nonce, input, parsed.signature)
}

/** The same for a request a paired server signed (1.0). */
internal fun ApplicationCall.signedPeerRequest(body: ByteArray): SignedPeerRequest {
    val header = request.header(HttpHeaders.Authorization) ?: throw unauthenticated(AuthFailure.MISSING)
    val parsed = PeerSignature.parse(header) ?: throw unauthenticated(AuthFailure.MALFORMED)
    val signedAt = try {
        Instant.ofEpochSecond(parsed.created)
    } catch (e: DateTimeException) {
        throw unauthenticated(AuthFailure.MALFORMED)
    }
    val input = PeerSignature.signingInput(request.httpMethod.value, request.path(), parsed.label, parsed.created, parsed.nonce, body)
    return SignedPeerRequest(parsed.label, signedAt, parsed.nonce, input, parsed.signature)
}

/** The label a paired server's signature names, for rate limits that run before anything is checked. */
internal fun ApplicationCall.peerLabelOrNull(): String? = request.header(HttpHeaders.Authorization)?.let(PeerSignature::parse)?.label

private fun unauthenticated(reason: AuthFailure) = DomainError.AuthenticationFailed(reason).toApiException()

/**
 * Nonces of the last minutes, in memory. A restart forgets them, which reopens replays only for
 * requests signed in the few minutes before it. When full of nonces that are all still valid, it
 * refuses new ones: forgetting one early would reopen its replay.
 */
class InMemoryNonceRegistry(private val now: () -> Instant, private val capacity: Int = 100_000) : NonceRegistry {
    private val expiries = HashMap<Pair<String, String>, Instant>()
    private val byExpiry = PriorityQueue<Pair<Instant, Pair<String, String>>>(compareBy { it.first })

    @Synchronized
    override fun register(signer: String, nonce: String, expiresAt: Instant): Boolean {
        val current = now()
        while (byExpiry.peek()?.first?.isAfter(current) == false) expiries.remove(byExpiry.poll().second)
        val key = signer to nonce
        if (key in expiries || expiries.size >= capacity) return false
        expiries[key] = expiresAt
        byExpiry.add(expiresAt to key)
        return true
    }
}

/** Tickets in memory, shared by the server screen that shows the QR and the API that consumes it. */
class InMemoryPairingTicketStore : PairingTicketStore {
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
        current.update { ticket -> if (ticket?.id == id) null else ticket }
    }

    override fun observe(id: String): Flow<PairingTicket?> = current.map { it?.takeIf { ticket -> ticket.id == id } }
}

/** Invites for other servers (1.0), in memory and apart from the trackers' ones. */
class InMemoryPeerTicketStore : PeerTicketStore {
    private val current = MutableStateFlow<PeerTicket?>(null)

    override fun issue(ticket: PeerTicket) {
        current.value = ticket
    }

    override fun get(id: String): PeerTicket? = current.value?.takeIf { it.id == id }

    override fun consume(id: String, publicKey: String): Boolean {
        var consumed = false
        current.update { ticket ->
            consumed = ticket?.id == id && (ticket.usedBy == null || ticket.usedBy == publicKey)
            if (consumed) ticket?.copy(usedBy = publicKey) else ticket
        }
        return consumed
    }

    /** A used ticket stays until it expires, so the server that used it can repeat a join whose answer it lost. */
    override fun withdraw(id: String) {
        current.update { if (it?.id == id && it.usedBy == null) null else it }
    }

    override fun observe(id: String): Flow<PeerTicket?> = current.map { it?.takeIf { ticket -> ticket.id == id } }
}
