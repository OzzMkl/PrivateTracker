package org.privatetracker.server.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.privatetracker.core.domain.model.RelayAddress
import org.privatetracker.core.domain.model.RelayRefusal
import org.privatetracker.core.domain.model.RelayState
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.relayLabel
import org.privatetracker.server.hostkeys.IdentityKeyFile
import org.privatetracker.server.tunnel.RelayNames
import org.privatetracker.server.tunnel.RelayTrustManager
import org.privatetracker.server.tunnel.RelayTunnel
import org.privatetracker.server.tunnel.Tunnel
import org.privatetracker.server.tunnel.TunnelLog
import org.privatetracker.server.tunnel.TunnelMessage
import org.privatetracker.server.tunnel.TunnelTarget
import org.privatetracker.server.tunnel.TunnelVisitors
import org.privatetracker.server.tunnel.closeQuietly
import org.privatetracker.server.tunnel.keyFingerprint
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val DOMAIN = "relay.test"

/** Prints nothing, keeps what the tunnel said for the assertions. */
private class Lines : TunnelLog {
    val lines = mutableListOf<String>()
    override fun info(message: String) = synchronized(lines) { lines += message; Unit }
    override fun warn(message: String, error: Throwable?) = synchronized(lines) { lines += message; Unit }
}

/**
 * A TLS server with [keys], answering each line with "<name>: <line>", as a PrivateTracker server's port
 * answers trackers; what the relay sits in front of. With [visitors], it adds who the tunnel says each
 * connection carries, as the server's HTTP layer asks.
 */
private class EchoServer(keys: X509ExtendedKeyManager, private val name: String, private val visitors: TunnelVisitors? = null) {
    private val socket = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), null, null) }
        .serverSocketFactory.createServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket
    val address = InetSocketAddress("127.0.0.1", socket.localPort)

    /** The address and port each connection came from. */
    val peers: MutableList<Pair<String, Int>> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                val peer = client.inetAddress.hostAddress to client.port
                peers += peer
                thread(isDaemon = true) {
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        val writer = it.getOutputStream().bufferedWriter()
                        while (true) {
                            val line = reader.readLine() ?: break
                            val from = visitors?.let { known -> " from " + known.visitor(peer.first, peer.second) }.orEmpty()
                            writer.write("$name: $line$from\n")
                            writer.flush()
                        }
                    }
                }
            }
        }
    }

    fun close() = closeQuietly(socket)
}

class RelayTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeables = mutableListOf<() -> Unit>()

    private val relayKeys by lazy { IdentityKeyFile(dir.resolve("relay.p12"), "relay", "PrivateTracker Relay", Instant::now) }
    private val serverKeys by lazy { IdentityKeyFile(dir.resolve("server.p12"), "server", "PrivateTracker Server", Instant::now) }
    private val relayFingerprint by lazy { keyFingerprint(relayKeys.publicKey())!! }
    private val serverFingerprint by lazy { keyFingerprint(serverKeys.publicKey())!! }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        closeables.reversed().forEach { it() }
    }

    private fun startRelay(allowed: Set<String> = setOf(serverFingerprint), port: Int = 0, routeMillis: Long = 15_000): Relay {
        val relay = Relay(RelaySettings(DOMAIN, port, "127.0.0.1", allowed, routeMillis = routeMillis), relayKeys.keyManager())
        relay.start()
        closeables += relay::stop
        return relay
    }

    private fun echo(name: String, visitors: TunnelVisitors? = null): EchoServer =
        EchoServer(serverKeys.keyManager(), name, visitors).also { closeables += it::close }

    private fun tunnel(
        relayPort: Int,
        targets: Map<TunnelTarget, InetSocketAddress>,
        relayFingerprint: String = this.relayFingerprint,
        log: TunnelLog = Lines(),
        visitors: TunnelVisitors = TunnelVisitors(),
        // DOMAIN resolves nowhere; the relay is on this machine.
        dial: (RelayAddress) -> Socket = { Socket().apply { connect(InetSocketAddress("127.0.0.1", it.port), 5_000) } },
    ): RelayTunnel {
        val tunnel = RelayTunnel(
            relay = RelayAddress(DOMAIN, relayPort),
            relayFingerprint = relayFingerprint,
            serverFingerprint = serverFingerprint,
            keyManager = serverKeys.keyManager(),
            targets = targets,
            log = log,
            dial = dial,
            pingEvery = 200.milliseconds,
            visitors = visitors,
        )
        tunnel.start(scope)
        return tunnel
    }

    private fun RelayTunnel.awaitState(test: (RelayState) -> Boolean): RelayState = runBlocking { withTimeout(15_000) { status.first(test) } }

    /** A tracker as the app is one: TLS to the relay's port, asking for [name], trusting only [pin]. */
    private fun visit(relayPort: Int, name: String, pin: String = serverFingerprint): Pair<String, String?> {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(RelayTrustManager(pin)), null) }
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", relayPort), 5_000); soTimeout = 10_000 }
        val socket = context.socketFactory.createSocket(raw, name, relayPort, true) as SSLSocket
        socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(name)) }
        socket.use {
            it.startHandshake()
            val presented = (it.session.peerCertificates.first() as java.security.cert.X509Certificate).keyFingerprint()!!
            it.outputStream.write("hola\n".encodeToByteArray())
            it.outputStream.flush()
            return presented to it.inputStream.bufferedReader().readLine()
        }
    }

    @Test
    fun `a tracker reaches its server through the relay, and TLS ends at the server, not the relay`() {
        val relay = startRelay()
        val api = echo("api")
        val tunnel = tunnel(relay.port, mapOf(TunnelTarget.API to api.address))

        val connected = assertIs<RelayState.Connected>(tunnel.awaitState { it is RelayState.Connected })
        assertEquals("https://${relayLabel(serverFingerprint)}.$DOMAIN:${relay.port}", connected.trackerUrl)
        assertNull(connected.webUrl)

        val (presented, answer) = visit(relay.port, "${relayLabel(serverFingerprint)}.$DOMAIN")
        // The key the tracker saw is the server's own: the relay held no key the tracker would accept.
        assertEquals(serverFingerprint, presented)
        assertEquals("api: hola", answer)
        assertEquals(setOf(serverFingerprint), relay.connectedServers)
        // Several at once, each on its own stream.
        val answers = java.util.Collections.synchronizedList(mutableListOf<String?>())
        (1..5).map { thread { answers += runCatching { visit(relay.port, "${relayLabel(serverFingerprint)}.$DOMAIN").second }.getOrNull() } }
            .forEach { it.join(10_000) }
        assertEquals(List(5) { "api: hola" }, answers.toList())
    }

    @Test
    fun `the server knows who each relayed connection carries, and only while it lasts`() {
        val relay = startRelay()
        val visitors = TunnelVisitors()
        val api = echo("api", visitors)
        tunnel(relay.port, mapOf(TunnelTarget.API to api.address), visitors = visitors).awaitState { it is RelayState.Connected }

        // The connection reached the server from this machine, yet it carries the visitor the relay saw.
        assertEquals("api: hola from 127.0.0.1", visit(relay.port, "${relayLabel(serverFingerprint)}.$DOMAIN").second)

        val (address, port) = api.peers.single()
        runBlocking { withTimeout(5_000) { while (visitors.visitor(address, port) != null) delay(50) } }
        assertNull(visitors.visitor(address, port + 1))
    }

    @Test
    fun `a tunnel stopped while it dials never comes up`() {
        val relay = startRelay()
        val dialing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tunnel = RelayTunnel(
            relay = RelayAddress(DOMAIN, relay.port),
            relayFingerprint = relayFingerprint,
            serverFingerprint = serverFingerprint,
            keyManager = serverKeys.keyManager(),
            targets = mapOf(TunnelTarget.API to echo("api").address),
            log = Lines(),
            dial = {
                dialing.countDown()
                release.await()
                Socket().apply { connect(InetSocketAddress("127.0.0.1", it.port), 5_000) }
            },
        )
        val job = tunnel.start(scope)
        assertTrue(dialing.await(5, TimeUnit.SECONDS))

        job.cancel()
        release.countDown()

        runBlocking { withTimeout(5_000) { job.join() } }
        assertEquals(RelayState.Off, tunnel.status.value)
        Thread.sleep(300)
        assertTrue(relay.connectedServers.isEmpty())
    }

    @Test
    fun `a connection that trickles bytes without ever saying where it goes is closed`() {
        val relay = startRelay(routeMillis = 500)
        val socket = Socket().apply { connect(InetSocketAddress("127.0.0.1", relay.port), 5_000); soTimeout = 5_000 }
        closeables += { closeQuietly(socket) }
        val started = System.nanoTime()

        // A TLS record header announcing a long ClientHello, then a byte now and then, each well within the read timeout.
        val closed = runCatching {
            socket.outputStream.write(byteArrayOf(22, 3, 1, 0x40, 0))
            repeat(50) {
                Thread.sleep(100)
                socket.outputStream.write(1)
            }
            socket.inputStream.read()
        }

        assertTrue(closed.isFailure || closed.getOrNull() == -1)
        assertTrue(System.nanoTime() - started < 4_000_000_000L)
    }

    @Test
    fun `addresses count one by one, IPv6 ones by their 64 bit prefix`() {
        fun key(address: String) = addressKey(InetAddress.getByName(address))

        assertEquals("203.0.113.7", key("203.0.113.7"))
        assertEquals(key("2001:db8:1:2::5"), key("2001:db8:1:2:ffff:ffff:ffff:1"))
        assertNotEquals(key("2001:db8:1:2::5"), key("2001:db8:1:3::5"))
        assertEquals("203.0.113.7", key("::ffff:203.0.113.7"))
    }

    @Test
    fun `a server not on the relay's list is refused, and says so`() {
        val relay = startRelay(allowed = setOf("0000-0000-0000-0000"))
        val tunnel = tunnel(relay.port, mapOf(TunnelTarget.API to echo("api").address))

        assertEquals(RelayState.Refused(RelayRefusal.NOT_ALLOWED), tunnel.awaitState { it is RelayState.Refused })
        assertTrue(relay.connectedServers.isEmpty())
    }

    @Test
    fun `a relay with another key is not trusted`() {
        val relay = startRelay()

        val tunnel = tunnel(relay.port, mapOf(TunnelTarget.API to echo("api").address), relayFingerprint = "0000-0000-0000-0000")

        assertEquals(RelayState.KeyMismatch, tunnel.awaitState { it == RelayState.KeyMismatch })
    }

    @Test
    fun `names the relay does not know, and a web app the server does not offer, get nowhere`() {
        val relay = startRelay()
        val tunnel = tunnel(relay.port, mapOf(TunnelTarget.API to echo("api").address))
        tunnel.awaitState { it is RelayState.Connected }

        listOf("0123456789abcdef.$DOMAIN", "web-${relayLabel(serverFingerprint)}.$DOMAIN", "${relayLabel(serverFingerprint)}.other.test", "example.org")
            .forEach { name -> assertFailsWith<IOException>(name) { visit(relay.port, name) } }
    }

    @Test
    fun `with the web app offered, browsers reach its port under the web name`() {
        val relay = startRelay()
        val tunnel = tunnel(relay.port, mapOf(TunnelTarget.API to echo("api").address, TunnelTarget.WEB to echo("web").address))

        val connected = assertIs<RelayState.Connected>(tunnel.awaitState { it is RelayState.Connected })

        assertEquals("https://web-${relayLabel(serverFingerprint)}.$DOMAIN:${relay.port}", connected.webUrl)
        assertEquals("web: hola", visit(relay.port, "web-${relayLabel(serverFingerprint)}.$DOMAIN").second)
        assertEquals("api: hola", visit(relay.port, "${relayLabel(serverFingerprint)}.$DOMAIN").second)
    }

    @Test
    fun `the tunnel comes back by itself after the relay restarts`() {
        val first = startRelay()
        val port = first.port
        val tunnel = tunnel(port, mapOf(TunnelTarget.API to echo("api").address))
        tunnel.awaitState { it is RelayState.Connected }

        first.stop()
        tunnel.awaitState { it !is RelayState.Connected }
        startRelay(port = port)

        tunnel.awaitState { it is RelayState.Connected }
        assertEquals("api: hola", visit(port, "${relayLabel(serverFingerprint)}.$DOMAIN").second)
    }

    @Test
    fun `the health check gets an answer without a certificate`() {
        val relay = startRelay()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(RelayTrustManager(relayFingerprint)), null) }
        val raw = Socket().apply { connect(InetSocketAddress("127.0.0.1", relay.port), 5_000); soTimeout = 5_000 }
        val socket = context.socketFactory.createSocket(raw, DOMAIN, relay.port, true) as SSLSocket
        socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(DOMAIN)) }

        socket.use {
            Tunnel.write(it.outputStream, TunnelMessage.Health)
            assertEquals(TunnelMessage.Ok, Tunnel.read(it.inputStream))
        }
    }

    @Test
    fun `the ClientHello a JDK client sends yields its server name, and anything else is refused`() {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        closeables += { closeQuietly(listener) }
        val captured = thread {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(RelayTrustManager("0000-0000-0000-0000")), null) }
            val socket = context.socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket
            socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName("0123456789abcdef.relay.test")) }
            runCatching { socket.startHandshake() }
            closeQuietly(socket)
        }
        val accepted = listener.accept()

        val hello = ClientHello.read(accepted.getInputStream())
        closeQuietly(accepted)
        captured.join(5_000)

        assertEquals("0123456789abcdef.relay.test", hello.serverName)
        assertEquals(22, hello.bytes[0].toInt())
        assertFailsWith<IOException> { ClientHello.read(ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".encodeToByteArray())) }
        assertFailsWith<IOException> { ClientHello.read(ByteArrayInputStream(byteArrayOf(22, 3, 1, 0, 5, 1, 0, 0, 9, 3))) }
    }

    @Test
    fun `the tunnel's lines keep their form, frozen with 1_0`() {
        fun line(message: TunnelMessage) = java.io.ByteArrayOutputStream().also { Tunnel.write(it, message) }.toByteArray().decodeToString()

        assertEquals(1, Tunnel.VERSION)
        assertEquals("{\"type\":\"hello\",\"version\":1,\"web\":true}\n", line(TunnelMessage.Hello(1, web = true)))
        assertEquals("{\"type\":\"ready\",\"version\":1}\n", line(TunnelMessage.Ready(1)))
        assertEquals("{\"type\":\"refused\",\"reason\":\"not-allowed\"}\n", line(TunnelMessage.Refused("not-allowed")))
        assertEquals("{\"type\":\"open\",\"stream\":\"s1\",\"target\":\"web\",\"from\":\"203.0.113.7\"}\n", line(TunnelMessage.Open("s1", TunnelTarget.WEB, "203.0.113.7")))
        assertEquals("{\"type\":\"data\",\"stream\":\"s1\"}\n", line(TunnelMessage.Data("s1")))
        listOf("ping", "pong", "health", "ok").zip(listOf(TunnelMessage.Ping, TunnelMessage.Pong, TunnelMessage.Health, TunnelMessage.Ok)).forEach { (type, message) ->
            assertEquals("{\"type\":\"$type\"}\n", line(message))
        }
        // A relay from before the visitor's address was sent, and fields a later version adds, still read.
        assertEquals(
            TunnelMessage.Open("s1", TunnelTarget.API),
            Tunnel.read(ByteArrayInputStream("{\"type\":\"open\",\"stream\":\"s1\",\"target\":\"api\",\"later\":1}\n".encodeToByteArray())),
        )
    }

    @Test
    fun `the relay's names lead to a server's label and its target, and to nothing else`() {
        assertEquals("0123456789abcdef" to TunnelTarget.API, RelayNames.parse("0123456789ABCDEF.relay.test", DOMAIN))
        assertEquals("0123456789abcdef" to TunnelTarget.WEB, RelayNames.parse("web-0123456789abcdef.relay.test", DOMAIN))
        listOf("relay.test", "x.relay.test", "0123456789abcdef.relay.test.evil", "a.0123456789abcdef.relay.test", "0123456789abcdefrelay.test")
            .forEach { assertNull(RelayNames.parse(it, DOMAIN), it) }
    }
}
