package org.privatetracker.server.relay

import org.privatetracker.core.domain.model.relayLabel
import org.privatetracker.server.tunnel.RefusalReason
import org.privatetracker.server.tunnel.RelayNames
import org.privatetracker.server.tunnel.Tunnel
import org.privatetracker.server.tunnel.TunnelMessage
import org.privatetracker.server.tunnel.TunnelTarget
import org.privatetracker.server.tunnel.closeQuietly
import org.privatetracker.server.tunnel.keyFingerprint
import org.privatetracker.server.tunnel.splice
import org.privatetracker.server.tunnel.useModernProtocols
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.concurrent.thread

/** What `serve` runs with. [allowed] are the fingerprints of the servers this relay carries. */
data class RelaySettings(
    /** The relay's own name; servers are reached at names under it. Lower case. */
    val domain: String,
    val port: Int = DEFAULT_PORT,
    val bindAddress: String = "0.0.0.0",
    val allowed: Set<String>,
    val maxConnections: Int = 1024,
    /** Per IPv4 address or IPv6 /64, before a connection proves to be an allowed server's. */
    val maxPerAddress: Int = 32,
    val maxStreamsPerServer: Int = 256,
    /** From accepting a connection to knowing where it goes, a server's TLS handshake included. */
    val routeMillis: Long = 15_000,
) {
    companion object {
        const val DEFAULT_PORT = 443
    }
}

/**
 * The relay, from 1.0 on: one port for everyone, sorted by the name each TLS connection asks for in its
 * ClientHello, which the relay reads and nothing more.
 *
 * - The relay's own [RelaySettings.domain]: a server's tunnel, or the relay's health check. TLS ends
 *   here, with the relay's key; a server proves itself with its identity key as client certificate,
 *   and only servers on the list get in.
 * - `<label>.<domain>` and `web-<label>.<domain>`: a tracker or a browser for that server. The relay
 *   asks the server for a data connection, hands it the ClientHello as it came, and from then on only
 *   copies bytes. TLS ends at the server, with the key trackers pinned.
 * - Anything else: closed.
 */
class Relay(private val settings: RelaySettings, private val keyManager: X509ExtendedKeyManager) {
    private val log = LoggerFactory.getLogger("Relay")
    private val servers = ConcurrentHashMap<String, ServerSession>()

    /** Per server label, across its reconnections: dialing again does not buy a server a fresh allowance. */
    private val streams = ConcurrentHashMap<String, AtomicInteger>()
    private val pending = ConcurrentHashMap<String, PendingStream>()
    private val connections = Semaphore(settings.maxConnections)
    private val perAddress = ConcurrentHashMap<String, Int>()
    private val random = SecureRandom()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "relay-connection").apply { isDaemon = true } }
    private val deadlines = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "relay-deadlines").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val tls: SSLContext = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(AnyClientCertificate), null) }
    private var listener: ServerSocket? = null

    @Volatile private var stopping = false

    /** The port it listens on, once started: the configured one, or the one the system picked for 0. */
    val port: Int get() = checkNotNull(listener).localPort

    /** The servers connected now, by fingerprint. */
    val connectedServers: Set<String> get() = servers.values.map { it.fingerprint }.toSet()

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(settings.bindAddress, settings.port), BACKLOG)
        listener = socket
        thread(name = "relay-accept", isDaemon = true) { accept(socket) }
        log.info("PrivateTracker Relay for {} listening on {}:{}, {} servers allowed", settings.domain, settings.bindAddress, port, settings.allowed.size)
    }

    fun stop() {
        if (stopping) return
        stopping = true
        closeQuietly(listener)
        servers.values.forEach(ServerSession::close)
        workers.shutdownNow()
        deadlines.shutdownNow()
        log.info("Stopped")
    }

    private fun accept(socket: ServerSocket) {
        while (!stopping) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                if (stopping) return
                // Out of file descriptors, most likely: the loop survives it.
                log.warn("Accept failed: {}", e.message)
                Thread.sleep(100)
                continue
            }
            val slot = Slot(addressKey(connection.inetAddress))
            if (!slot.enter()) {
                closeQuietly(connection)
                continue
            }
            try {
                workers.execute {
                    try {
                        handle(connection, slot)
                    } catch (e: Exception) {
                        log.debug("Connection ended: {}", e.message)
                        closeQuietly(connection)
                    } finally {
                        slot.leave()
                    }
                }
            } catch (e: RejectedExecutionException) {
                slot.leave()
                closeQuietly(connection)
            }
        }
    }

    /**
     * A connection's place in the limits: one of [RelaySettings.maxConnections], and one of its
     * address's until it proves to be an allowed server's, which dials one connection per visitor.
     */
    private inner class Slot(private val address: String) {
        @Volatile private var countsForAddress = false
        @Volatile private var holdsConnection = false

        fun enter(): Boolean {
            var admitted = false
            perAddress.compute(address) { _, count ->
                val now = (count ?: 0) + 1
                admitted = now <= settings.maxPerAddress
                if (admitted) now else count
            }
            if (!admitted) return false
            countsForAddress = true
            if (!connections.tryAcquire()) {
                leaveAddress()
                return false
            }
            holdsConnection = true
            return true
        }

        @Synchronized
        fun leaveAddress() {
            if (!countsForAddress) return
            countsForAddress = false
            perAddress.computeIfPresent(address) { _, count -> if (count <= 1) null else count - 1 }
        }

        @Synchronized
        fun leave() {
            leaveAddress()
            if (holdsConnection) connections.release()
            holdsConnection = false
        }
    }

    private fun handle(connection: Socket, slot: Slot) {
        connection.tcpNoDelay = true
        connection.soTimeout = HELLO_MILLIS
        // Each read has a timeout of its own; this one bounds them all, so a byte now and then cannot
        // keep a connection that has not yet said where it goes.
        val deadline = deadlines.schedule({ closeQuietly(connection) }, settings.routeMillis, TimeUnit.MILLISECONDS)
        val hello = try {
            ClientHello.read(connection.getInputStream())
        } catch (e: IOException) {
            deadline.cancel(false)
            closeQuietly(connection)
            return
        }
        val name = hello.serverName
        if (name == settings.domain) {
            tunnelEndpoint(connection, hello, slot) { deadline.cancel(false) }
            return
        }
        deadline.cancel(false)
        val route = name?.let { RelayNames.parse(it, settings.domain) }
        if (route == null) {
            closeQuietly(connection)
            return
        }
        visitor(connection, hello, route.first, route.second)
    }

    /**
     * A connection of a server (control or data) or of the health check: TLS ends here. [routed] once
     * its first message has come.
     */
    private fun tunnelEndpoint(connection: Socket, hello: ClientHello, slot: Slot, routed: () -> Unit) {
        val socket = tls.socketFactory.createSocket(connection, ByteArrayInputStream(hello.bytes), true) as SSLSocket
        socket.useClientMode = false
        // Wanted, not needed: the health check has none. Servers without one are refused below.
        socket.wantClientAuth = true
        socket.useModernProtocols()
        try {
            socket.startHandshake()
        } catch (e: IOException) {
            closeQuietly(socket)
            return
        }
        val certificate = try {
            socket.session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (e: SSLPeerUnverifiedException) {
            null
        }
        val first = try {
            Tunnel.read(socket.inputStream)
        } catch (e: IOException) {
            null
        }
        routed()
        when (first) {
            TunnelMessage.Health -> {
                Tunnel.write(socket.outputStream, TunnelMessage.Ok)
                closeQuietly(socket)
            }
            is TunnelMessage.Hello -> control(socket, connection, certificate?.keyFingerprint(), first, slot)
            is TunnelMessage.Data -> data(socket, certificate?.keyFingerprint(), first, slot)
            else -> closeQuietly(socket)
        }
    }

    private fun control(socket: SSLSocket, raw: Socket, fingerprint: String?, hello: TunnelMessage.Hello, slot: Slot) {
        if (fingerprint == null || fingerprint !in settings.allowed) {
            log.warn("Refused server {}: not on this relay's list", fingerprint ?: "without a certificate")
            refuse(socket, RefusalReason.NOT_ALLOWED)
            return
        }
        if (hello.version != Tunnel.VERSION) {
            refuse(socket, RefusalReason.VERSION)
            return
        }
        slot.leaveAddress()
        val session = ServerSession(fingerprint, socket, raw, hello.web)
        try {
            // Ready before anyone can find the session: no visitor's Open may reach the server first.
            session.send(TunnelMessage.Ready(Tunnel.VERSION))
            // A server that dialed again replaces its old tunnel, which may not have noticed yet that it broke.
            servers.put(session.label, session)?.close()
            log.info("Server {} connected{}", fingerprint, if (hello.web) ", with its web app" else "")
            socket.soTimeout = CONTROL_IDLE_MILLIS
            while (true) {
                when (Tunnel.read(socket.inputStream) ?: break) {
                    TunnelMessage.Ping -> session.send(TunnelMessage.Pong)
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            // Silent too long, or gone.
        } finally {
            if (servers.remove(session.label, session)) log.info("Server {} disconnected", fingerprint)
            session.close()
        }
    }

    private fun refuse(socket: SSLSocket, reason: String) {
        try {
            Tunnel.write(socket.outputStream, TunnelMessage.Refused(reason))
        } catch (e: IOException) {
            // It hung up first.
        }
        closeQuietly(socket)
    }

    /** A server's data connection for one stream: handed to the visitor waiting for it, if it is that server's. */
    private fun data(socket: SSLSocket, fingerprint: String?, data: TunnelMessage.Data, slot: Slot) {
        val waiting = pending.remove(data.stream)
        if (fingerprint == null || waiting == null || waiting.label != relayLabel(fingerprint)) {
            closeQuietly(socket)
            return
        }
        slot.leaveAddress()
        socket.soTimeout = 0
        if (!waiting.future.complete(socket)) closeQuietly(socket)
    }

    /** A tracker or browser for a server: joined to a data connection of that server, bytes untouched. */
    private fun visitor(connection: Socket, hello: ClientHello, label: String, target: TunnelTarget) {
        val session = servers[label]
        if (session == null || (target == TunnelTarget.WEB && !session.web)) {
            closeQuietly(connection)
            return
        }
        val count = streams.computeIfAbsent(label) { AtomicInteger() }
        if (count.incrementAndGet() > settings.maxStreamsPerServer) {
            count.decrementAndGet()
            closeQuietly(connection)
            return
        }
        try {
            val stream = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(STREAM_ID_BYTES).also(random::nextBytes))
            val future = CompletableFuture<SSLSocket>()
            pending[stream] = PendingStream(label, future)
            val dataConnection = try {
                session.send(TunnelMessage.Open(stream, target, from = connection.inetAddress.hostAddress))
                future.get(OPEN_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: IOException) {
                null
            } catch (e: TimeoutException) {
                null
            } catch (e: ExecutionException) {
                null
            } finally {
                pending.remove(stream)
            }
            if (dataConnection == null) {
                // One that came just as the wait ended would stay open with nobody to close it.
                if (!future.completeExceptionally(TimeoutException())) closeQuietly(future.getNow(null))
                closeQuietly(connection)
                return
            }
            try {
                dataConnection.outputStream.write(hello.bytes)
                dataConnection.outputStream.flush()
            } catch (e: IOException) {
                closeQuietly(dataConnection)
                closeQuietly(connection)
                return
            }
            splice(connection, dataConnection)
        } finally {
            count.decrementAndGet()
        }
    }

    private class PendingStream(val label: String, val future: CompletableFuture<SSLSocket>)

    /**
     * A connected server's control connection. Lines to it go through a queue and a thread of their own,
     * so a server that stops reading has its tunnel closed instead of holding up every visitor for it.
     */
    private class ServerSession(val fingerprint: String, private val socket: SSLSocket, private val raw: Socket, val web: Boolean) {
        val label = relayLabel(fingerprint)
        private val outbox = LinkedBlockingQueue<TunnelMessage>(OUTBOX)
        private val writer = thread(name = "relay-control", isDaemon = true, start = false) {
            try {
                while (true) Tunnel.write(socket.outputStream, outbox.take())
            } catch (e: InterruptedException) {
                // Closed.
            } catch (e: IOException) {
                // Gone.
            } finally {
                close()
            }
        }

        @Volatile private var closed = false

        init {
            writer.start()
        }

        fun send(message: TunnelMessage) {
            if (closed) throw IOException("The tunnel is closed")
            if (!outbox.offer(message)) {
                close()
                throw IOException("The server is not reading its tunnel")
            }
        }

        /** The raw socket: closing the TLS one would wait for a write that may never end. */
        fun close() {
            closed = true
            closeQuietly(raw)
            if (Thread.currentThread() !== writer) writer.interrupt()
        }
    }

    private companion object {
        const val BACKLOG = 256
        const val HELLO_MILLIS = 10_000

        /** Lines waiting for a server's control connection; more means it stopped reading. */
        const val OUTBOX = 64

        /** Servers ping every 25 s; three missed and the tunnel counts as gone. */
        const val CONTROL_IDLE_MILLIS = 90_000

        /** How long a visitor waits for its server to dial back. */
        const val OPEN_MILLIS = 10_000L
        const val STREAM_ID_BYTES = 16
    }
}

/** IPv6 by its /64: a home or a phone gets a whole one, and would otherwise get as many places as it likes. */
internal fun addressKey(address: InetAddress): String =
    if (address is Inet6Address) {
        InetAddress.getByAddress(address.address.copyOf(16).also { it.fill(0, 8, 16) }).hostAddress + "/64"
    } else {
        address.hostAddress
    }
