package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.MAX_ADVERTISED_ADDRESSES
import org.privatetracker.core.domain.model.PAIRING_SECRET_BYTES
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.model.PeerJoin
import org.privatetracker.core.domain.model.PeerTicket
import org.privatetracker.core.domain.model.PeerWelcome
import org.privatetracker.core.domain.model.SignedPeerRequest
import org.privatetracker.core.domain.model.SyncBatch
import org.privatetracker.core.domain.model.SyncCursor
import org.privatetracker.core.domain.model.isValidPeerPairingProof
import org.privatetracker.core.domain.model.peerLabel
import org.privatetracker.core.domain.model.peerPairingProof
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.core.domain.port.PeerGateway
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.port.SignatureVerifier
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.repository.DeviceRepository
import org.privatetracker.core.domain.repository.PeerRepository
import org.privatetracker.core.domain.repository.PeerTicketStore
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.SyncRepository
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.domain.validation.TrackerConfigValidator
import org.privatetracker.core.domain.validation.validateDeviceName
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

// Paired servers, from 1.0 on. Two servers pair through a one-time invite, as a tracker pairs with
// its server, and from then on exchange their devices and locations both ways about once a minute,
// whichever of them can reach the other starting the exchange.

/** Issues a one-time ticket and the invite another server reads to pair with this one. */
class CreatePeerInvite(
    private val tickets: PeerTicketStore,
    private val serverKeys: ServerKeys,
    private val serverConfig: ServerConfigRepository,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val validity: Duration = CreatePairingInvite.VALIDITY,
) {
    suspend operator fun invoke(serverUrls: List<String>): Outcome<PairingInvite> {
        if (serverUrls.isEmpty()) return DomainError.Validation("serverUrls", FieldViolation.REQUIRED).asFailure()
        val serverKey = try {
            serverKeys.publicKey()
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        val ticket = PeerTicket(randomToken(TICKET_ID_BYTES), randomToken(PAIRING_SECRET_BYTES), clock.now().plus(validity))
        tickets.issue(ticket)
        return PairingInvite(serverConfig.get().serverName, serverUrls, serverKey, ticket.id, ticket.secret, ticket.expiresAt).asSuccess()
    }

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private companion object {
        const val TICKET_ID_BYTES = 9
    }
}

/** Takes the invite's ticket off the table when it leaves the screen. */
class WithdrawPeerInvite(private val tickets: PeerTicketStore) {
    operator fun invoke(ticketId: String) = tickets.withdraw(ticketId)
}

/** The server that used a ticket, once one did; null while the invite is still waiting. */
class ObservePeerJoined(private val tickets: PeerTicketStore, private val peers: PeerRepository) {
    operator fun invoke(ticketId: String): Flow<Peer?> =
        combine(tickets.observe(ticketId), peers.observeAll()) { ticket, all ->
            ticket?.usedBy?.let { key -> all.firstOrNull { it.publicKey == key } }
        }.distinctUntilChanged()
}

class ObservePeers(private val peers: PeerRepository) {
    operator fun invoke(): Flow<List<Peer>> = peers.observeAll()
}

/**
 * Forgets a paired server. What came from it stays; it gets nothing more, and its next exchange is
 * told this server no longer knows it.
 */
class RemovePeer(private val peers: PeerRepository) {
    suspend operator fun invoke(publicKey: String): Outcome<Unit> =
        if (peers.delete(publicKey)) Unit.asSuccess() else DomainError.PeerNotFound.asFailure()
}

/**
 * On the inviting server: lets in the server that read its invite. That server signs the request with
 * the key it joins with and proves it knows the ticket's secret without sending it; the ticket then
 * belongs to that key alone, so only the same server can repeat a join whose answer it lost.
 */
class AcceptPeer(
    private val peers: PeerRepository,
    private val tickets: PeerTicketStore,
    private val serverKeys: ServerKeys,
    private val serverConfig: ServerConfigRepository,
    private val verifier: SignatureVerifier,
    private val verifySignature: VerifyRequestSignature,
    private val ownAddresses: suspend () -> List<String>,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: SignedPeerRequest, join: PeerJoin): Outcome<PeerWelcome> {
        if (!verifier.isValidKey(join.publicKey)) return DomainError.Validation("public_key", FieldViolation.INVALID_FORMAT).asFailure()
        if (request.label != peerLabel(join.publicKey)) return DomainError.AuthenticationFailed(AuthFailure.INVALID).asFailure()
        // The cheap checks first: without a live ticket and its secret, nobody gets a signature checked
        // or a nonce remembered, which the trackers' requests share.
        val now = clock.now()
        val ticket = tickets.get(join.ticketId)?.takeIf {
            now.isBefore(it.expiresAt) && (it.usedBy == null || it.usedBy == join.publicKey) &&
                isValidPeerPairingProof(it.secret, join.publicKey, join.proof)
        } ?: return DomainError.PairingInvalid.asFailure()
        // Using it again is only the same server retrying, and only while it is still paired: one the
        // owner removed, having seen the wrong fingerprint, must not come back with the same ticket.
        if (ticket.usedBy != null && peers.get(join.publicKey) == null) return DomainError.PairingInvalid.asFailure()
        verifySignature(request, join.publicKey).let { if (it is Outcome.Failure) return it }
        validateDeviceName("name", join.serverName)?.let { return DomainError.Validation(listOf(it)).asFailure() }
        val own = try {
            serverKeys.publicKey()
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        // An invite read by the server that made it.
        if (join.publicKey == own) return DomainError.PairingInvalid.asFailure()
        if (peers.get(join.publicKey) == null && peers.all().size >= MAX_PEERS) {
            return DomainError.Validation("peers", FieldViolation.OUT_OF_RANGE).asFailure()
        }
        if (!tickets.consume(ticket.id, join.publicKey)) return DomainError.PairingInvalid.asFailure()
        peers.save(Peer(join.publicKey, join.serverName.trim(), cleanAddresses(join.addresses), now))
        return PeerWelcome(serverConfig.get().serverName, ownAddresses()).asSuccess()
    }
}

/**
 * On the joining server: pairs with the server of [invite][PairingInvite], whose key it pins from the
 * invite and checks in every TLS handshake. Tries the invite's addresses in order.
 */
class JoinPeer(
    private val peers: PeerRepository,
    private val gateway: PeerGateway,
    private val serverKeys: ServerKeys,
    private val serverConfig: ServerConfigRepository,
    private val ownAddresses: suspend () -> List<String>,
    private val clock: Clock,
) {
    suspend operator fun invoke(invite: PairingInvite): Outcome<Peer> {
        if (!clock.now().isBefore(invite.expiresAt)) return DomainError.PairingInvalid.asFailure()
        val own = try {
            serverKeys.publicKey()
        } catch (e: DeviceKeyException) {
            return DomainError.DeviceKeyUnavailable.asFailure()
        }
        if (invite.serverKey == own) return DomainError.PairingInvalid.asFailure()
        val join = PeerJoin(
            ticketId = invite.ticketId,
            serverName = serverConfig.get().serverName,
            publicKey = own,
            addresses = ownAddresses(),
            proof = peerPairingProof(invite.secret, own),
        )
        var failure: DomainError = DomainError.Network.Unreachable
        for (url in invite.serverUrls) {
            when (val answer = gateway.join(url, invite.serverKey, join)) {
                is Outcome.Success -> {
                    // The address that answered first: it worked just now.
                    val addresses = cleanAddresses(listOf(url) + answer.value.addresses)
                    val name = answer.value.serverName.trim().takeIf { validateDeviceName("name", it) == null } ?: invite.serverName
                    val peer = Peer(invite.serverKey, name, addresses, clock.now())
                    peers.save(peer)
                    return (peers.get(invite.serverKey) ?: peer).asSuccess()
                }
                is Outcome.Failure -> {
                    failure = answer.error
                    // The server answered and said no: another address would say the same.
                    if (!answer.error.worthAnotherAddress()) return answer
                }
            }
        }
        return failure.asFailure()
    }
}

/**
 * What both sides of an exchange do: put together what to send, and take in what came. Both run
 * inside a transaction, so a batch is stored together with how far it got, or not at all.
 */
class SyncBatches(
    private val devices: DeviceRepository,
    private val sync: SyncRepository,
    private val peers: PeerRepository,
    private val serverConfig: ServerConfigRepository,
    private val verifier: SignatureVerifier,
    private val validator: LocationValidator,
    private val geofences: EvaluateGeofences,
    private val clock: Clock,
    private val locationLimit: Int = LOCATIONS_PER_BATCH,
    private val deviceLimit: Int = DEVICES_PER_BATCH,
) {
    /** This server's devices and locations the peer does not have yet, from where it last said it was. */
    suspend fun outgoing(peer: Peer, addresses: List<String>): SyncBatch {
        val numbered = sync.numbering()
        val claimed = peer.sync.sent
        // It says it has more of this server's data than this server ever numbered: this server was
        // restored from a backup. Everything goes again; the other side keeps each thing once.
        val reset = claimed.locations > numbered.locations || claimed.devices > numbered.devices
        val from = if (reset) SyncCursor.START else claimed
        val changes = sync.deviceChangesAfter(from.devices, deviceLimit)
        val located = sync.locationsAfter(from.locations, peer.publicKey, locationLimit)
        // A location needs its device on the other side, even one that has not changed lately.
        val sent = changes.devices.map { it.id }.toSet()
        val owners = located.locations.map { it.deviceId }.distinct().filter { it !in sent }.mapNotNull { devices.get(it) }
        return SyncBatch(
            serverName = serverConfig.get().serverName,
            addresses = addresses,
            have = peer.sync.received,
            upTo = SyncCursor(located.upTo, changes.upTo),
            devices = changes.devices + owners,
            removedDevices = changes.removed,
            locations = located.locations,
            more = !changes.complete || !located.complete,
            reset = reset,
        )
    }

    /**
     * Takes in a batch from [peer]: removals first, then devices, the latest change of each winning,
     * then locations, once each. Records how far both servers got. Returns the geofence crossings
     * found, for alerts once the transaction is through.
     */
    suspend fun incoming(peer: Peer, batch: SyncBatch): List<GeofenceEventView> {
        val now = clock.now()
        // Nothing counts as later than now here: a date in the future would win over every real change
        // for good. Clocks on automatic time differ by seconds, which this costs nothing.
        for (sent in batch.removedDevices) {
            val removal = sent.copy(removedAt = minOf(sent.removedAt, now))
            val local = devices.get(removal.id)
            // Back since: it registered again after the removal, on some server.
            if (local != null && local.changedAt.isAfter(removal.removedAt)) continue
            sync.remove(removal)
        }
        for (incoming in batch.devices) merge(incoming, now)

        val cutoff = now.minus(serverConfig.get().retention)
        val known = HashMap<DeviceId, Device?>()
        val accepted = LinkedHashMap<DeviceId, MutableList<Location>>()
        for (location in batch.locations) {
            val device = known.getOrPut(location.deviceId) { devices.get(location.deviceId) } ?: continue
            // As the API: only an approved device's positions count, and alert.
            if (device.approval != DeviceApproval.APPROVED) continue
            // Never later than now: a date in the future would keep it from the retention purge.
            val receivedAt = minOf(location.receivedAt ?: now, now)
            // Older than this server keeps, or something no tracker could have sent.
            if (receivedAt.isBefore(cutoff) || validator.validate(location) != null) continue
            val stored = location.copy(receivedAt = receivedAt)
            if (sync.insertLocation(stored, peer.publicKey)) accepted.getOrPut(device.id) { mutableListOf() } += stored
        }
        val crossings = mutableListOf<GeofenceEventView>()
        for ((id, locations) in accepted) {
            val current = devices.getWithLastLocation(id) ?: continue
            val newest = locations.maxBy { it.recordedAt }
            if (current.lastLocation == null || newest.recordedAt.isAfter(current.lastLocation.recordedAt)) devices.setLastLocation(id, newest.id)
            crossings += geofences(current.device, locations)
        }
        peers.update(peer.publicKey) {
            it.copy(
                name = batch.serverName.trim().takeIf { name -> validateDeviceName("name", name) == null } ?: it.name,
                addresses = cleanAddresses(batch.addresses).ifEmpty { it.addresses },
                sync = it.sync.copy(
                    lastExchangeAt = now,
                    // Whichever side started it, the two are in step: a peer this one cannot reach
                    // (behind CGNAT) but that reaches it is fine, not failing.
                    lastError = null,
                    received = if (batch.reset) {
                        batch.upTo
                    } else {
                        SyncCursor(maxOf(it.sync.received.locations, batch.upTo.locations), maxOf(it.sync.received.devices, batch.upTo.devices))
                    },
                    // As the peer said: lower than before only if it lost data, which then goes again.
                    sent = batch.have,
                ),
            )
        }
        return crossings
    }

    private suspend fun merge(sent: Device, now: Instant) {
        if (sent.publicKey != null && !verifier.isValidKey(sent.publicKey)) return
        // Seen in the future is seen now, or it would show online for good; the same for every date.
        val incoming = sent.copy(
            createdAt = minOf(sent.createdAt, now),
            updatedAt = sent.updatedAt?.let { minOf(it, now) },
            approvalChangedAt = sent.approvalChangedAt?.let { minOf(it, now) },
            lastSeenAt = sent.lastSeenAt?.let { minOf(it, now) },
        )
        val removed = sync.removal(incoming.id)
        if (removed != null && !incoming.changedAt.isAfter(removed.removedAt)) return
        val local = devices.get(incoming.id)
        // Never another key for a known device, as on registration: device ids travel in the clear, and
        // a new key would let its holder send as that device. A new key comes only after a removal.
        if (local?.publicKey != null && incoming.publicKey != local.publicKey) return
        val merged = if (local == null) {
            incoming
        } else {
            // What the tracker reports and what the owner decided change apart: a tracker checking in
            // with a new version must not undo a rejection made on another server meanwhile.
            val details = if (incoming.changedAt.isAfter(local.changedAt)) incoming else local
            val decision = if (incoming.approvalDecidedAt.isAfter(local.approvalDecidedAt)) incoming else local
            details.copy(
                approval = decision.approval,
                approvalChangedAt = decision.approvalDecidedAt,
                createdAt = minOf(local.createdAt, incoming.createdAt),
                lastSeenAt = latest(local.lastSeenAt, incoming.lastSeenAt),
            )
        }
        if (merged != local) sync.putDevice(merged)
    }

    private fun latest(a: Instant?, b: Instant?) = listOfNotNull(a, b).maxOrNull()

    companion object {
        /** About 150 KB of JSON: well under the request limit, and quick on a phone. */
        const val LOCATIONS_PER_BATCH = 500
        const val DEVICES_PER_BATCH = 200
    }
}

/**
 * Starts an exchange with a paired server: sends what it lacks, takes in its answer, and repeats while
 * either side has more. Tries the peer's addresses in order, the one that worked last first.
 */
class ExchangeWithPeer(
    private val peers: PeerRepository,
    private val batches: SyncBatches,
    private val gateway: PeerGateway,
    private val transaction: TransactionRunner,
    private val alerts: GeofenceAlerts,
    private val ownAddresses: suspend () -> List<String>,
    private val clock: Clock,
    private val maxRounds: Int = MAX_ROUNDS,
) {
    /** One per peer: a slow server holds up only its own exchanges. */
    private val running = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend operator fun invoke(publicKey: String): Outcome<Unit> = running.getOrPut(publicKey) { Mutex() }.withLock {
        val start = peers.get(publicKey) ?: return@withLock DomainError.PeerNotFound.asFailure()
        val addresses = ownAddresses()
        var address = start.addresses.firstOrNull() ?: run {
            // It joined without an address to give (its server was stopped): it calls, or nobody does.
            failed(publicKey, DomainError.NotConfigured)
            return@withLock DomainError.NotConfigured.asFailure()
        }
        repeat(maxRounds) {
            val outgoing = transaction.run { peers.get(publicKey)?.let { batches.outgoing(it, addresses) } }
                ?: return@withLock DomainError.PeerNotFound.asFailure()
            val answer = when (val sent = send(publicKey, address, outgoing)) {
                is Outcome.Success -> sent.value
                is Outcome.Failure -> {
                    failed(publicKey, sent.error)
                    return@withLock sent
                }
            }
            address = answer.first
            val crossings = transaction.run {
                val peer = peers.get(publicKey) ?: return@run null
                batches.incoming(peer, answer.second)
            } ?: return@withLock DomainError.PeerNotFound.asFailure()
            peers.update(publicKey) { peer ->
                // The address that answered goes first, for the next time.
                peer.copy(
                    addresses = (listOf(address) + peer.addresses).distinct().take(MAX_ADVERTISED_ADDRESSES),
                    sync = peer.sync.copy(lastAttemptAt = clock.now(), lastError = null),
                )
            }
            crossings.filter { it.event.alerted }.takeIf { it.isNotEmpty() }?.let(alerts::alert)
            if (!outgoing.more && !answer.second.more) return@withLock Unit.asSuccess()
        }
        Unit.asSuccess()
    }

    /** The answer and the address that gave it. */
    private suspend fun send(publicKey: String, preferred: String, batch: SyncBatch): Outcome<Pair<String, SyncBatch>> {
        val known = peers.get(publicKey)?.addresses.orEmpty()
        var failure: DomainError = DomainError.Network.Unreachable
        for (url in (listOf(preferred) + known).distinct()) {
            when (val answer = gateway.exchange(url, publicKey, batch)) {
                is Outcome.Success -> return (url to answer.value).asSuccess()
                is Outcome.Failure -> {
                    failure = answer.error
                    if (!answer.error.worthAnotherAddress()) return answer
                }
            }
        }
        return failure.asFailure()
    }

    private suspend fun failed(publicKey: String, error: DomainError) {
        peers.update(publicKey) { it.copy(sync = it.sync.copy(lastAttemptAt = clock.now(), lastError = error.code)) }
    }

    companion object {
        /** A minute's catching up: 50 batches of 500 locations each way. */
        const val MAX_ROUNDS = 50
    }
}

/** Answers an exchange a paired server started: takes in its batch and sends back what it lacks. */
class AnswerPeerExchange(
    private val peers: PeerRepository,
    private val batches: SyncBatches,
    private val verifySignature: VerifyRequestSignature,
    private val transaction: TransactionRunner,
    private val alerts: GeofenceAlerts,
    private val ownAddresses: suspend () -> List<String>,
) {
    /** Whether a paired server goes by [label]; asked before a request's body is even read. */
    suspend fun knows(label: String): Boolean = peers.getByLabel(label) != null

    /** The paired server that signed [request]: checked before its batch is even read. */
    suspend fun authenticate(request: SignedPeerRequest): Outcome<Peer> {
        val peer = peers.getByLabel(request.label) ?: return DomainError.PeerUnknown.asFailure()
        verifySignature(request, peer.publicKey).let { if (it is Outcome.Failure) return it }
        return peer.asSuccess()
    }

    suspend operator fun invoke(peer: Peer, batch: SyncBatch): Outcome<SyncBatch> {
        val addresses = ownAddresses()
        val (crossings, answer) = transaction.run {
            val current = peers.get(peer.publicKey) ?: return@run null
            val crossings = batches.incoming(current, batch)
            val updated = peers.get(peer.publicKey) ?: return@run null
            crossings to batches.outgoing(updated, addresses)
        } ?: return DomainError.PeerUnknown.asFailure()
        crossings.filter { it.event.alerted }.takeIf { it.isNotEmpty() }?.let(alerts::alert)
        return answer.asSuccess()
    }
}

/**
 * What a server runs about once a minute: an exchange with each paired server that nobody, neither
 * side, had one with in the last [interval]. Both sides run it, so the one that can reach the other
 * keeps them in step even when the other is behind CGNAT.
 */
class SyncWithPeers(
    private val peers: PeerRepository,
    private val exchange: ExchangeWithPeer,
    private val clock: Clock,
    private val interval: Duration = INTERVAL,
) {
    suspend operator fun invoke() {
        for (peer in peers.all()) {
            val now = clock.now()
            val last = listOfNotNull(peer.sync.lastExchangeAt, peer.sync.lastAttemptAt).maxOrNull()
            // A little early is fine: two timers a few seconds apart must not skip each other's minute.
            if (last != null && Duration.between(last, now) < interval.minusSeconds(EARLY_SECONDS)) continue
            exchange(peer.publicKey)
        }
    }

    companion object {
        val INTERVAL: Duration = Duration.ofMinutes(1)
        private const val EARLY_SECONDS = 5L
    }
}

/** Nobody there, or somebody else: an address that moved on, as LAN addresses do. Anything else the peer itself said. */
private fun DomainError.worthAnotherAddress(): Boolean = this is DomainError.Network || this == DomainError.ServerIdentityMismatch

/** At most this many paired servers: enough for a family's, and a bound on what a stolen ticket costs. */
const val MAX_PEERS = 16

/** Addresses another server gave: https only, the form trackers use, no repeats, at most as many as a tracker keeps. */
internal fun cleanAddresses(raw: List<String>): List<String> =
    raw.map(TrackerConfigValidator::normalizeServerUrl)
        .filter { TrackerConfigValidator.validateServerUrl(it) == null }
        .distinct()
        .take(MAX_ADVERTISED_ADDRESSES)
