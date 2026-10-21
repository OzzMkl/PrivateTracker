package org.privatetracker.server.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.repository.PeerTicketStore
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.CreatePeerInvite
import org.privatetracker.core.domain.usecase.server.ObservePairedDevice
import org.privatetracker.core.domain.usecase.server.ObservePeerJoined
import org.privatetracker.core.domain.usecase.server.WithdrawPairingInvite
import org.privatetracker.core.domain.usecase.server.WithdrawPeerInvite
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * QR invites shown in the web app: a phone's, or from 1.0 another server's ([T] is what used it). As
 * on the phone, the QR is worth something only while someone looks at it: its page asks for news
 * every few seconds, and an invite nobody asked about for [ABANDONED_AFTER] (tab closed, another
 * page opened) is withdrawn. A reload asks again at once, so it keeps the invite. The ticket store
 * is the server's one: an invite on the command line replaces the web's, and the other way round.
 */
class WebInvites<T : Any>(
    private val createInvite: suspend (urls: List<String>) -> Outcome<PairingInvite>,
    private val observeUsedBy: (ticketId: String) -> Flow<T?>,
    private val withdrawInvite: (ticketId: String) -> Unit,
    /** Null when the ticket is off the table; else whether something used it. */
    private val ticketUsed: (ticketId: String) -> Boolean?,
    private val inviteUrls: suspend () -> List<String>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val checkEvery: kotlin.time.Duration = 5.seconds,
) {
    private class Shown(val invite: PairingInvite, @Volatile var lastAskedAt: Instant, val watchdog: Job)

    private val shown = mutableMapOf<String, Shown>()

    /** One invite made at a time: two quick clicks must not leave the newer code without its watchdog. */
    private val creating = Mutex()

    sealed interface State<out T> {
        data class Waiting(val invite: PairingInvite) : State<Nothing>
        data class Used<T>(val by: T) : State<T>
        data object Expired : State<Nothing>

        /** Withdrawn: replaced by a newer invite, here or on the command line, or abandoned. */
        data object Ended : State<Nothing>
    }

    /** The URLs a new invite would carry; empty when the server knows no address the other side can use. */
    suspend fun urls(): List<String> = inviteUrls()

    suspend fun create(): Outcome<PairingInvite> = creating.withLock {
        val created = createInvite(inviteUrls())
        if (created is Outcome.Success) {
            val invite = created.value
            val watchdog = scope.launch { watch(invite.ticketId) }
            synchronized(shown) {
                // A new invite withdrew the others; their pages say so, and their watchdogs end.
                shown.values.forEach { it.watchdog.cancel() }
                shown.clear()
                shown[invite.ticketId] = Shown(invite, clock.now(), watchdog)
            }
        }
        created
    }

    /** What the page of [ticketId] shows now; asking counts as someone still looking. */
    suspend fun state(ticketId: String): State<T>? {
        val entry = synchronized(shown) { shown[ticketId] } ?: return null
        val now = clock.now()
        entry.lastAskedAt = now
        observeUsedBy(ticketId).first()?.let { return State.Used(it) }
        return when {
            // Before "gone": the watchdog withdraws an expired code too, and it still expired.
            !now.isBefore(entry.invite.expiresAt) -> {
                withdrawInvite(ticketId)
                State.Expired
            }
            ticketUsed(ticketId) == null -> State.Ended
            else -> State.Waiting(entry.invite)
        }
    }

    fun cancel(ticketId: String) = withdrawInvite(ticketId)

    private suspend fun watch(ticketId: String) {
        while (true) {
            delay(checkEvery)
            val entry = synchronized(shown) { shown[ticketId] } ?: return
            val now = clock.now()
            val used = ticketUsed(ticketId) ?: return
            if (used) return
            if (Duration.between(entry.lastAskedAt, now) > ABANDONED_AFTER || !now.isBefore(entry.invite.expiresAt)) {
                withdrawInvite(ticketId)
                return
            }
        }
    }

    companion object {
        /** The page asks every 2 s; this leaves room for a slow network or a reload. */
        val ABANDONED_AFTER: Duration = Duration.ofSeconds(20)
    }
}

typealias WebPairing = WebInvites<Device>

/** Invites for phones, with the server's tracker tickets. */
fun WebPairing(
    createInvite: CreatePairingInvite,
    observePairedDevice: ObservePairedDevice,
    withdrawInvite: WithdrawPairingInvite,
    tickets: PairingTicketStore,
    inviteUrls: suspend () -> List<String>,
    clock: Clock,
    scope: CoroutineScope,
    checkEvery: kotlin.time.Duration = 5.seconds,
): WebPairing = WebInvites(
    createInvite = { createInvite(it) },
    observeUsedBy = { observePairedDevice(it) },
    withdrawInvite = { withdrawInvite(it) },
    ticketUsed = { id -> tickets.get(id)?.let { it.usedBy != null } },
    inviteUrls = inviteUrls,
    clock = clock,
    scope = scope,
    checkEvery = checkEvery,
)

/** Invites for other servers (1.0), with the server's peer tickets. */
fun WebPeerInvites(
    createInvite: CreatePeerInvite,
    observeJoined: ObservePeerJoined,
    withdrawInvite: WithdrawPeerInvite,
    tickets: PeerTicketStore,
    inviteUrls: suspend () -> List<String>,
    clock: Clock,
    scope: CoroutineScope,
    checkEvery: kotlin.time.Duration = 5.seconds,
): WebInvites<Peer> = WebInvites(
    createInvite = { createInvite(it) },
    observeUsedBy = { observeJoined(it) },
    withdrawInvite = { withdrawInvite(it) },
    ticketUsed = { id -> tickets.get(id)?.let { it.usedBy != null } },
    inviteUrls = inviteUrls,
    clock = clock,
    scope = scope,
    checkEvery = checkEvery,
)
