package org.privatetracker.server.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.repository.PairingTicketStore
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.ObservePairedDevice
import org.privatetracker.core.domain.usecase.server.WithdrawPairingInvite
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * QR invites shown in the web app. As on the phone, the QR is worth something only while someone
 * looks at it: its page asks for news every few seconds, and an invite nobody asked about for
 * [ABANDONED_AFTER] (tab closed, another page opened) is withdrawn. A reload asks again at once, so
 * it keeps the invite. The ticket store is the server's one: a `pair` on the command line replaces
 * the web's invite, and the other way round.
 */
class WebPairing(
    private val createInvite: CreatePairingInvite,
    private val observePairedDevice: ObservePairedDevice,
    private val withdrawInvite: WithdrawPairingInvite,
    private val tickets: PairingTicketStore,
    private val inviteUrls: () -> List<String>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val checkEvery: kotlin.time.Duration = 5.seconds,
) {
    private class Shown(val invite: PairingInvite, @Volatile var lastAskedAt: Instant, val watchdog: Job)

    private val shown = mutableMapOf<String, Shown>()

    /** One invite made at a time: two quick clicks must not leave the newer code without its watchdog. */
    private val creating = Mutex()

    sealed interface State {
        data class Waiting(val invite: PairingInvite) : State
        data class Paired(val device: Device) : State
        data object Expired : State

        /** Withdrawn: replaced by a newer invite, here or on the command line, or abandoned. */
        data object Ended : State
    }

    /** The URLs a new invite would carry; empty when the server knows no address phones can use. */
    fun urls(): List<String> = inviteUrls()

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
    suspend fun state(ticketId: String): State? {
        val entry = synchronized(shown) { shown[ticketId] } ?: return null
        val now = clock.now()
        entry.lastAskedAt = now
        observePairedDevice(ticketId).first()?.let { return State.Paired(it) }
        return when {
            // Before "gone": the watchdog withdraws an expired code too, and it still expired.
            !now.isBefore(entry.invite.expiresAt) -> {
                withdraw(ticketId)
                State.Expired
            }
            tickets.get(ticketId) == null -> State.Ended
            else -> State.Waiting(entry.invite)
        }
    }

    fun cancel(ticketId: String) = withdraw(ticketId)

    private fun withdraw(ticketId: String) {
        withdrawInvite(ticketId)
    }

    private suspend fun watch(ticketId: String) {
        while (true) {
            delay(checkEvery)
            val entry = synchronized(shown) { shown[ticketId] } ?: return
            val now = clock.now()
            val used = tickets.get(ticketId)?.usedBy != null
            if (used || tickets.get(ticketId) == null) return
            if (Duration.between(entry.lastAskedAt, now) > ABANDONED_AFTER || !now.isBefore(entry.invite.expiresAt)) {
                withdraw(ticketId)
                return
            }
        }
    }

    companion object {
        /** The page asks every 2 s; this leaves room for a slow network or a reload. */
        val ABANDONED_AFTER: Duration = Duration.ofSeconds(20)
    }
}
