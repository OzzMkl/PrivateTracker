package org.privatetracker.server.tunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.privatetracker.core.domain.model.RelayAddress
import org.privatetracker.core.domain.model.RelayRefusal
import org.privatetracker.core.domain.model.RelayState
import org.privatetracker.core.domain.model.relayTrackerUrl
import org.privatetracker.core.domain.model.relayWebUrl
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where the tunnel says what it does: Android's Log on a phone, SLF4J on Linux. */
interface TunnelLog {
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
}

/**
 * A server's tunnel to its relay, from 1.0 on: one control connection, kept open with pings and dialed
 * again after any failure, and one more connection per tracker the relay announces, joined to the
 * server's own port on this machine. The server proves itself to the relay with its identity key as
 * TLS client certificate; the relay must show the key of [relayFingerprint]. Trackers' TLS passes
 * through untouched and ends at the server, as without a relay.
 *
 * [targets] are the local ports per kind of visitor; the web app is offered only when it is in there.
 * [visitors] tells the server's HTTP layer who each of those connections carries. At most [maxStreams]
 * visitors go through at once; the relay turns away the ones this server does not take.
 */
class RelayTunnel(
    private val relay: RelayAddress,
    private val relayFingerprint: String,
    private val serverFingerprint: String,
    keyManager: X509ExtendedKeyManager,
    private val targets: Map<TunnelTarget, InetSocketAddress>,
    private val log: TunnelLog,
    private val dial: (RelayAddress) -> Socket = ::dialRelay,
    private val pingEvery: Duration = 25.seconds,
    private val visitors: TunnelVisitors = TunnelVisitors(),
    private val maxStreams: Int = MAX_STREAMS,
) {
    private val tls: SSLContext = clientTlsContext(keyManager, relayFingerprint)
    private val state = MutableStateFlow<RelayState>(RelayState.Connecting)
    private val slots = Semaphore(maxStreams)

    // Each visitor blocks a thread while its bytes flow: threads of their own, so a crowd of them
    // cannot starve the server's other work of Dispatchers.IO.
    private val streamThreads = Dispatchers.IO.limitedParallelism(maxStreams)

    @Volatile private var connectedOnce = false

    val status: StateFlow<RelayState> = state.asStateFlow()

    /** Runs until the returned job, or [scope], is cancelled; data connections then end too. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        // Streams outlive a dropped control connection: a tracker mid-upload finishes.
        val streams = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        var backoff = 1.seconds
        try {
            while (isActive) {
                state.value = RelayState.Connecting
                val ended = try {
                    session(streams)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure(e)
                }
                state.value = ended
                // A tunnel that worked and then dropped is dialed again soon; one that never comes up, ever slower.
                if (connectedOnce) backoff = 1.seconds
                connectedOnce = false
                val wait = when {
                    // Only the relay's owner can fix these; asking every second helps nobody.
                    ended is RelayState.Refused || ended == RelayState.KeyMismatch -> RETRY_REFUSED
                    else -> backoff
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                delay(wait)
            }
        } finally {
            state.value = RelayState.Off
        }
    }

    /** One control connection, from dialing to its end; what the state becomes then. */
    private suspend fun session(streams: CoroutineScope): RelayState = coroutineScope {
        val socket = connect()
        // Undispatched, so it runs even if a cancel came during the dial, and closes what the dial opened.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { closeOnCancel(socket) }
        try {
            ensureActive()
            val output = socket.outputStream
            val input = socket.inputStream
            val send = { message: TunnelMessage -> synchronized(output) { Tunnel.write(output, message) } }
            send(TunnelMessage.Hello(Tunnel.VERSION, web = TunnelTarget.WEB in targets))
            when (val answer = Tunnel.read(input)) {
                is TunnelMessage.Ready -> Unit
                is TunnelMessage.Refused -> return@coroutineScope RelayState.Refused(
                    if (answer.reason == RefusalReason.VERSION) RelayRefusal.VERSION else RelayRefusal.NOT_ALLOWED,
                )
                else -> throw IOException("The relay answered $answer")
            }
            val connected = RelayState.Connected(
                trackerUrl = relayTrackerUrl(relay, serverFingerprint),
                webUrl = if (TunnelTarget.WEB in targets) relayWebUrl(relay, serverFingerprint) else null,
            )
            state.value = connected
            connectedOnce = true
            log.info("Relay $relay connected; trackers reach this server at ${connected.trackerUrl}")
            // A relay that went quiet for three pings is gone, even if TCP has not noticed.
            socket.soTimeout = (pingEvery * 3).inWholeMilliseconds.toInt()
            val pinger = launch(Dispatchers.IO) {
                while (true) {
                    delay(pingEvery)
                    send(TunnelMessage.Ping)
                }
            }
            try {
                while (true) {
                    when (val message = Tunnel.read(input) ?: break) {
                        is TunnelMessage.Open -> if (slots.tryAcquire()) {
                            streams.launch(streamThreads) { stream(message) }.invokeOnCompletion { slots.release() }
                        }
                        TunnelMessage.Ping -> send(TunnelMessage.Pong)
                        else -> Unit
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw IOException("The relay stopped answering", e)
            } finally {
                pinger.cancel()
            }
            RelayState.Unreachable("The relay closed the connection")
        } finally {
            closer.cancel()
            closeQuietly(socket)
        }
    }

    /** A data connection for one tracker or browser: to the relay, then to this server's own port. */
    private suspend fun stream(open: TunnelMessage.Open) {
        val target = targets[open.target] ?: return
        val toRelay = try {
            connect()
        } catch (e: IOException) {
            log.warn("No data connection to the relay for a ${open.target} visitor: ${e.message}")
            return
        }
        coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) { closeOnCancel(toRelay) }
            try {
                Tunnel.write(toRelay.outputStream, TunnelMessage.Data(open.stream))
                val local = Socket()
                try {
                    local.connect(target, LOCAL_CONNECT_MILLIS)
                    // Known before the first byte of the visitor reaches the server.
                    val address = local.localAddress.hostAddress
                    val visitor = TunnelVisitors.sanitize(open.from)
                    visitors.add(address, local.localPort, visitor)
                    try {
                        splice(toRelay, local)
                    } finally {
                        visitors.remove(address, local.localPort, visitor)
                    }
                } finally {
                    closeQuietly(local)
                }
            } catch (e: IOException) {
                log.warn("A ${open.target} visitor through the relay was cut off: ${e.message}")
            } finally {
                closer.cancel()
                closeQuietly(toRelay)
            }
        }
    }

    private fun connect(): SSLSocket {
        val raw = dial(relay)
        try {
            raw.soTimeout = HANDSHAKE_MILLIS
            val socket = tls.socketFactory.createSocket(raw, relay.host, relay.port, true) as SSLSocket
            socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(relay.host)) }
            socket.useModernProtocols()
            socket.startHandshake()
            // The handshake's timeout stays for the first answer: a relay that goes quiet after the
            // handshake must not leave the tunnel waiting for ever.
            return socket
        } catch (e: Exception) {
            closeQuietly(raw)
            throw e
        }
    }

    private fun failure(error: Exception): RelayState {
        if (generateSequence<Throwable>(error) { it.cause }.any { it is RelayKeyMismatchException }) {
            log.warn("The relay at $relay showed a key other than $relayFingerprint; not connecting")
            return RelayState.KeyMismatch
        }
        log.warn("Relay $relay unreachable: ${error.message}")
        return RelayState.Unreachable(error.message ?: error.javaClass.simpleName)
    }

    companion object {
        private val MAX_BACKOFF = 1.minutes
        private val RETRY_REFUSED = 5.minutes
        private const val HANDSHAKE_MILLIS = 15_000
        private const val LOCAL_CONNECT_MILLIS = 5_000

        /** Trackers and browsers through the relay at once: plenty for a family, few enough for a phone. */
        const val MAX_STREAMS = 64
    }
}

/** Closes [socket] when the coroutine running this is cancelled, which unblocks whatever reads it. */
private suspend fun closeOnCancel(socket: Socket) {
    try {
        awaitCancellation()
    } finally {
        closeQuietly(socket)
    }
}

private const val CONNECT_MILLIS = 10_000

private fun dialRelay(relay: RelayAddress): Socket = Socket().apply { connect(InetSocketAddress(relay.host, relay.port), CONNECT_MILLIS) }
