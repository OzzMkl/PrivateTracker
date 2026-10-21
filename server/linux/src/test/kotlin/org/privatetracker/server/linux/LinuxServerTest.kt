package org.privatetracker.server.linux

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RelayAddress
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.relayLabel
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeServerDiscovery
import org.privatetracker.core.domain.testing.FixedBattery
import org.privatetracker.core.domain.testing.InMemoryIdentityRepository
import org.privatetracker.core.domain.testing.InMemoryOutboxRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerStateRepository
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.aFix
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.usecase.server.GeofenceDraft
import org.privatetracker.core.domain.usecase.tracker.FailOverServerAddress
import org.privatetracker.core.domain.usecase.tracker.GetServerEncryptionKey
import org.privatetracker.core.domain.usecase.tracker.PairWithServer
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.usecase.tracker.VerifyServerIdentity
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.network.KtorServerGateway
import org.privatetracker.core.network.OkHttpPinnedClients
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.server.hostkeys.IdentityKeyFile
import org.privatetracker.server.hostkeys.SelfSignedCertificate
import org.privatetracker.server.linux.admin.AdminClient
import org.privatetracker.server.linux.admin.AdminReply
import org.privatetracker.server.linux.admin.AdminRequest
import org.privatetracker.server.linux.admin.AlreadyRunningException
import org.privatetracker.server.linux.keys.FileServerKeys
import org.privatetracker.server.linux.keys.PemFiles
import org.privatetracker.server.relay.Relay
import org.privatetracker.server.relay.RelaySettings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Linux server as its owner runs it, against a tracker as a phone runs it: the app's HTTP client
 * with pinned TLS and its use cases, over the network to localhost. The owner acts through the admin
 * socket, as the command line does.
 */
private const val WEB_PASSWORD = "caballo bateria grapa correcta"

class LinuxServerTest {
    @TempDir
    lateinit var dir: Path

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "https://127.0.0.1:$port"
    /** False once a tracker "leaves home": the LAN name home.lan.test stops resolving. */
    @Volatile private var atHome = true

    // Names under relay.test resolve to this machine, as a relay's wildcard DNS would to its host; so
    // does home.lan.test, the server's LAN name, while the tracker is at home.
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when {
            hostname.endsWith(".relay.test") -> listOf(InetAddress.getLoopbackAddress())
            hostname == "home.lan.test" -> if (atHome) listOf(InetAddress.getLoopbackAddress()) else throw UnknownHostException(hostname)
            else -> Dns.SYSTEM.lookup(hostname)
        }
    }
    private val clients = OkHttpPinnedClients("PrivateTracker-Test", dns = dns)
    private val running = mutableListOf<LinuxServer>()

    private val webPort = ServerSocket(0).use { it.localPort }
    private val webUrl = "https://127.0.0.1:$webPort"

    private fun startServer(
        acceptNewDevices: Boolean = true,
        web: WebSettings? = null,
        relay: Pair<String, String>? = null,
        publicUrls: List<String>? = null,
        name: String = "Casa",
        dataDir: Path = dir,
        serverPort: Int = port,
    ): ServerGraph {
        val config = ServerConfig(
            serverName = name, port = serverPort, bindAddress = "127.0.0.1", acceptNewDevices = acceptNewDevices,
            relayAddress = relay?.first.orEmpty(), relayFingerprint = relay?.second.orEmpty(),
        )
        val settings = ServeSettings(config, publicUrls = publicUrls ?: listOf("https://127.0.0.1:$serverPort"), web = web)
        val data = DataDirectory(dataDir).also { it.prepare() }
        val graph = ServerGraph(settings, data, peerClients = OkHttpPinnedClients("PrivateTracker-Test", dns = dns))
        // The relay's name resolves nowhere: the tunnel dials this machine.
        val dial = { address: RelayAddress -> Socket().apply { connect(InetSocketAddress("127.0.0.1", address.port), 5_000) } }
        LinuxServer(graph, "test", relayDial = dial).also { it.start() }.let(running::add)
        return graph
    }

    private fun stopServer() = running.removeLast().stop()

    @AfterEach
    fun tearDown() {
        running.reversed().forEach { it.stop() }
        clients.close()
    }

    private val admin get() = AdminClient(DataDirectory(dir).adminSocket)

    /** A phone in Tracker mode: the app's use cases over in-memory storage, talking to the server for real. */
    private inner class Tracker(config: TrackerConfig = TrackerConfig(deviceName = "Pixel de Ana"), id: DeviceId = DEVICE_A) {
        private val clock = Clock.System
        private val ids = SequentialIdGenerator()
        private val keys = InMemoryDeviceKeys()
        private val gateway = KtorServerGateway(clients, keys, clock)
        val outbox = InMemoryOutboxRepository()
        private val state = InMemoryTrackerStateRepository()
        val config = InMemoryTrackerConfigRepository(config)
        private val identity = GetOrCreateDeviceIdentity(InMemoryIdentityRepository(id), ids)
        private val record = RecordLocation(outbox, state, this.config, identity, FixedBattery(), LocationValidator(clock), ids)
        private val verifyServer = VerifyServerIdentity(gateway, EcdsaP256)
        private val encryption = GetServerEncryptionKey(state, verifyServer, clock, this.config)
        private val register = RegisterDevice(gateway, this.config, state, identity, keys, verifyServer, encryption, AppInfo(Platform.ANDROID, "test"), clock)
        private val uploadPending = UploadPendingLocations(outbox, gateway, this.config, state, register, encryption, identity, clock)
        private val pairWith = PairWithServer(verifyServer, this.config, register, clock)

        fun pair(invite: PairingInvite) = runBlocking { pairWith(invite) }

        fun recordAndUpload(fixes: Int): UploadResult = runBlocking {
            val now = clock.now()
            repeat(fixes) { record(aFix(latitude = 19.4326 + it / 1000.0, recordedAt = now.minusSeconds((fixes - it) * 10L))) }
            uploadPending()
        }

        fun upload(): UploadResult = runBlocking { uploadPending() }

        private val failOverServerAddress = FailOverServerAddress(this.config, verifyServer, FakeServerDiscovery(), clock)

        /** What the app does when its server stops answering: another known address where it proves its key. */
        fun failOver(): Boolean = runBlocking { failOverServerAddress() }

        /** Fixes at these latitudes, a minute apart and ending now, then one upload. */
        fun walk(vararg latitudes: Double): UploadResult = runBlocking {
            val now = clock.now()
            latitudes.forEachIndexed { i, latitude -> record(aFix(latitude = latitude, recordedAt = now.minusSeconds((latitudes.size - i) * 60L))) }
            uploadPending()
        }
    }

    /** The web app as a browser sees it: TLS to the key [pin] names, no redirects followed, cookies by hand. */
    private fun browser(pin: ServerPin) = clients.clientFor(pin).config { followRedirects = false }

    private fun identityPin(graph: ServerGraph) = ServerPin.Key(runBlocking { graph.serverKeys.publicKey() })

    private suspend fun HttpClient.signIn(name: String = "ana", password: String = WEB_PASSWORD): String {
        val response = submitForm("$webUrl/login", Parameters.build { append("name", name); append("password", password) }) {
            header("Sec-Fetch-Site", "same-origin")
        }
        assertEquals(HttpStatusCode.SeeOther, response.status)
        return response.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("__Host-pt-session=") }.substringAfter('=').substringBefore(';')
    }

    private suspend fun HttpClient.page(path: String, session: String) = get("$webUrl$path") {
        header(HttpHeaders.Accept, "text/html")
        header(HttpHeaders.Cookie, "__Host-pt-session=$session")
    }

    /** Runs `pair` (or `peers invite`) as the command line does, on its own thread; its replies arrive in order. */
    private fun startPairing(request: AdminRequest = AdminRequest.Pair): Pair<AdminReply.Invite, LinkedBlockingQueue<AdminReply>> {
        val replies = LinkedBlockingQueue<AdminReply>()
        thread(isDaemon = true) { admin.exchange(request) { replies.put(it) } }
        val invite = assertIs<AdminReply.Invite>(replies.poll(10, TimeUnit.SECONDS))
        return invite to replies
    }

    private fun devices() = assertIs<AdminReply.Devices>(admin.single(AdminRequest.Devices)).devices

    @Test
    fun `a phone pairs through the command line's QR and its fixes land in server dot db`() {
        startServer()
        val (invite, replies) = startPairing()
        val tracker = Tracker()

        val scanned = assertNotNull(PairingUri.parse(invite.uri))
        val result = tracker.pair(scanned).successValue()

        assertEquals(DeviceApproval.APPROVED, result.approval)
        assertEquals("Pixel de Ana", assertIs<AdminReply.Paired>(replies.poll(10, TimeUnit.SECONDS)).device.name)
        assertEquals(UploadResult.Completed(sent = 3, rejected = 0, hasMore = false), tracker.recordAndUpload(3))
        val row = devices().single()
        assertEquals("APPROVED" to "ONLINE", row.approval to row.status)
        assertEquals(invite.fingerprint, assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).fingerprint)
    }

    @Test
    fun `a phone set up by hand waits until the owner approves it from the command line`() {
        startServer()
        val fingerprint = assertNotNull(assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).fingerprint)
        val tracker = Tracker(TrackerConfig(serverUrl = url, deviceName = "Moto de Luis", serverFingerprint = fingerprint))

        assertEquals(UploadResult.RetryLater(DomainError.DevicePendingApproval), tracker.recordAndUpload(2))
        assertEquals("PENDING", devices().single().approval)

        val approved = assertIs<AdminReply.Done>(admin.single(AdminRequest.Approve(DEVICE_A.value.take(6))))
        assertEquals("Moto de Luis", approved.devices.single().name)
        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), tracker.upload())

        assertIs<AdminReply.Done>(admin.single(AdminRequest.Reject("moto de luis")))
        assertEquals(UploadResult.Blocked(DomainError.DeviceRejected), tracker.recordAndUpload(1))
    }

    @Test
    fun `after rotate-key trackers fetch the new key by themselves`() {
        startServer()
        val (invite) = startPairing()
        val tracker = Tracker()
        tracker.pair(assertNotNull(PairingUri.parse(invite.uri))).successValue()
        assertEquals(UploadResult.Completed(sent = 1, rejected = 0, hasMore = false), tracker.recordAndUpload(1))
        val before = assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).encryptionKeyId

        val rotated = assertIs<AdminReply.Done>(admin.single(AdminRequest.RotateKey)).encryptionKeyId

        assertTrue(rotated != null && rotated != before)
        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), tracker.recordAndUpload(2))
    }

    @Test
    fun `a restart keeps the identity, the devices and their history`() {
        val graph = startServer()
        val (invite) = startPairing()
        val tracker = Tracker()
        tracker.pair(assertNotNull(PairingUri.parse(invite.uri))).successValue()
        tracker.recordAndUpload(2)
        stopServer()

        val restarted = startServer()

        assertEquals(invite.fingerprint, assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).fingerprint)
        // Paired before the restart, the tracker goes on with no new pairing.
        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), tracker.recordAndUpload(2))
        assertTrue(graph !== restarted)
        val stored = runBlocking { restarted.getDeviceDetail(DEVICE_A) }.successValue()
        assertEquals("ONLINE", stored.overview.status.name)
        assertEquals(1, devices().size)
    }

    @Test
    fun `closing pair withdraws its invite, so a photo of the QR is worthless`() {
        startServer()
        var invite: AdminReply.Invite? = null
        // The command line stops at the invite, as when Ctrl+C is pressed while the QR shows.
        runCatching { admin.exchange(AdminRequest.Pair) { reply -> invite = reply as AdminReply.Invite; throw InterruptedException() } }
        val scanned = assertNotNull(PairingUri.parse(assertNotNull(invite).uri))

        // The server notices the hang-up on its own thread, within milliseconds.
        Thread.sleep(500)

        assertEquals(DomainError.PairingInvalid, Tracker().pair(scanned).failureError())
        assertTrue(devices().isEmpty())
    }

    @Test
    fun `the read endpoints answer no one, not even another account on the same host`() {
        startServer()
        val (invite) = startPairing()
        Tracker().pair(assertNotNull(PairingUri.parse(invite.uri))).successValue()
        val pin = ServerPin.Key(assertNotNull(PairingUri.parse(invite.uri)).serverKey)

        val answer = runBlocking { clients.clientFor(pin).get("$url${ApiV1.DEVICES}") }

        assertEquals(HttpStatusCode.Forbidden, answer.status)
        assertTrue("LOCAL_ONLY" in runBlocking { answer.bodyAsText() })
    }

    @Test
    fun `a device named after another one's id is never picked by that id`() {
        startServer()
        val fingerprint = assertNotNull(assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).fingerprint)
        val manual = TrackerConfig(serverUrl = url, serverFingerprint = fingerprint)
        Tracker(manual.copy(deviceName = "Pixel de Ana")).recordAndUpload(1)
        // Anyone on the network may ask to join, under any name: here, the first characters of Ana's id.
        Tracker(manual.copy(deviceName = DEVICE_A.value.take(8)), id = DEVICE_B).recordAndUpload(1)

        val refused = assertIs<AdminReply.Error>(admin.single(AdminRequest.Approve(DEVICE_A.value.take(8))))

        assertTrue("usa el id completo" in refused.message)
        assertTrue(devices().all { it.approval == "PENDING" })
        assertEquals("Pixel de Ana", assertIs<AdminReply.Done>(admin.single(AdminRequest.Approve(DEVICE_A.value))).devices.single().name)
    }

    @Test
    fun `with new devices off only a QR gets a phone in`() {
        startServer(acceptNewDevices = false)
        val fingerprint = assertNotNull(assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).fingerprint)

        val manual = Tracker(TrackerConfig(serverUrl = url, deviceName = "Moto de Luis", serverFingerprint = fingerprint), id = DEVICE_B)
        assertTrue(manual.recordAndUpload(1) !is UploadResult.Completed)
        assertTrue(devices().isEmpty())

        val (invite) = startPairing()
        assertEquals(DeviceApproval.APPROVED, Tracker().pair(assertNotNull(PairingUri.parse(invite.uri))).successValue().approval)
    }

    @Test
    fun `a second pair replaces the first, which says so instead of waiting for a dead code`() {
        startServer()
        val (first, firstReplies) = startPairing()
        val (second) = startPairing()

        assertIs<AdminReply.Error>(firstReplies.poll(10, TimeUnit.SECONDS))
        assertEquals(DomainError.PairingInvalid, Tracker().pair(assertNotNull(PairingUri.parse(first.uri))).failureError())
        assertEquals(DeviceApproval.APPROVED, Tracker().pair(assertNotNull(PairingUri.parse(second.uri))).successValue().approval)
    }

    @Test
    fun `the web app signs in over TLS on a port of its own and shows the paired phone`() {
        val graph = startServer(web = WebSettings(webPort, "127.0.0.1"))
        val (invite) = startPairing()
        val tracker = Tracker()
        tracker.pair(assertNotNull(PairingUri.parse(invite.uri))).successValue()
        tracker.recordAndUpload(2)
        assertIs<AdminReply.Users>(admin.single(AdminRequest.AddUser("Ana", WEB_PASSWORD)))
        val status = assertIs<AdminReply.Status>(admin.single(AdminRequest.Status))
        assertEquals(webPort, status.webPort)
        assertEquals(1, status.webAccounts)

        runBlocking {
            // Pinned to the identity key: without the owner's own certificate, the web port shows the identity's.
            val browser = browser(identityPin(graph))
            val session = browser.signIn()
            val map = browser.page("/", session)
            assertEquals(HttpStatusCode.OK, map.status)
            assertContains(map.bodyAsText(), "Pixel de Ana")
            assertNull(map.headers["Strict-Transport-Security"])
            val server = browser.page("/server", session).bodyAsText()
            assertContains(server, assertNotNull(status.fingerprint))
            // Shown in lines of eight bytes, to read against the browser's certificate details.
            assertNotNull(status.webCertificateSha256).split(':').chunked(8).forEach { assertContains(server, it.joinToString(":")) }
            // The tracker protocol is not on the web port, nor the web app on the trackers' port.
            assertEquals(HttpStatusCode.NotFound, browser.get("$webUrl${ApiV1.HEALTH}").status)
            assertEquals(HttpStatusCode.NotFound, browser.get("$url/login").status)
        }
    }

    @Test
    fun `an open page hears of a crossing as the phone uploads it, and the event says it alerted`() {
        val graph = startServer(web = WebSettings(webPort, "127.0.0.1"))
        val (invite) = startPairing()
        val tracker = Tracker()
        tracker.pair(assertNotNull(PairingUri.parse(invite.uri))).successValue()
        admin.single(AdminRequest.AddUser("ana", WEB_PASSWORD))
        val home = GeoPoint(19.4326, -99.1332)
        runBlocking {
            graph.saveGeofence(GeofenceDraft(null, "Casa", GeofenceShape.Circle(home, 200.0), null, notifyOnEnter = true, notifyOnExit = true)).successValue()
        }
        val outside = home.latitude + 0.0036

        runBlocking {
            val browser = browser(identityPin(graph))
            val session = browser.signIn()
            browser.prepareGet("$webUrl/live") { header(HttpHeaders.Cookie, "__Host-pt-session=$session") }.execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val channel = response.bodyAsChannel()
                suspend fun next(): Pair<String, String> {
                    var event = ""
                    while (true) {
                        val line = withTimeout(20_000) { channel.readLine() } ?: error("The stream ended")
                        if (line.startsWith("event: ")) event = line.removePrefix("event: ")
                        if (line.startsWith("data: ")) return event to line.removePrefix("data: ")
                    }
                }
                assertEquals("devices", next().first)
                assertTrue(graph.liveAlerts.listening)

                // Three fixes settle the phone outside, three more bring it in.
                assertIs<UploadResult.Completed>(tracker.walk(outside, outside, outside, home.latitude, home.latitude, home.latitude))

                var event = next()
                while (event.first == "devices") event = next()
                assertEquals("alert", event.first)
                assertContains(event.second, "\"geofence\":\"Casa\"")
                assertContains(event.second, "\"transition\":\"ENTER\"")
            }
            val stored = graph.observeGeofenceEvents(10).first().single()
            assertTrue(stored.event.alerted)
        }
    }

    @Test
    fun `a phone pairs with the QR the web page draws, and the page says so`() {
        val graph = startServer(web = WebSettings(webPort, "127.0.0.1"))
        admin.single(AdminRequest.AddUser("ana", WEB_PASSWORD))

        runBlocking {
            val browser = browser(identityPin(graph))
            val session = browser.signIn()
            val created = browser.post("$webUrl/pair") {
                header("Sec-Fetch-Site", "same-origin")
                header(HttpHeaders.Cookie, "__Host-pt-session=$session")
            }
            val page = browser.page(assertNotNull(created.headers[HttpHeaders.Location]), session).bodyAsText()

            // What a phone's camera reads: the drawing itself, not the link printed next to it.
            val scanned = assertNotNull(PairingUri.parse(decodeSvgQr(page)))
            assertEquals(DeviceApproval.APPROVED, Tracker().pair(scanned).successValue().approval)

            val status = browser.get("$webUrl/pair/${scanned.ticketId}/status") {
                header("HX-Request", "true")
                header(HttpHeaders.Cookie, "__Host-pt-session=$session")
            }.bodyAsText()
            assertContains(status, "Emparejado: Pixel de Ana")
        }
    }

    /** Reads the QR of the first SVG in [html], module by module as qrSvg draws it, the way a camera would. */
    private fun decodeSvgQr(html: String): String {
        val svg = assertNotNull(Regex("<svg[^>]*viewBox=\"0 0 (\\d+) (\\d+)\"[^>]*>.*?</svg>").find(html))
        val size = svg.groupValues[1].toInt()
        val scale = 6
        val pixels = IntArray(size * scale * size * scale) { 0xffffff }
        Regex("M(\\d+) (\\d+)h(\\d+)").findAll(svg.value).forEach { run ->
            val (x, y, width) = run.destructured
            for (module in x.toInt() until x.toInt() + width.toInt()) {
                for (dy in 0 until scale) for (dx in 0 until scale) pixels[(y.toInt() * scale + dy) * size * scale + module * scale + dx] = 0
            }
        }
        val image = RGBLuminanceSource(size * scale, size * scale, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(image))).text
    }

    @Test
    fun `the web port shows the owner's certificate when given one, with HSTS, and trackers keep the identity`() {
        val keys = EcdsaP256.generateKeyPair()
        val files = PemFiles(dir.resolve("casa.crt"), dir.resolve("casa.key"))
        Files.createDirectories(dir)
        Files.writeString(files.certificate, PemWriter.certificate(SelfSignedCertificate.create(keys, java.time.Instant.now())))
        Files.writeString(files.key, PemWriter.sec1(keys))
        val graph = startServer(web = WebSettings(webPort, "127.0.0.1", files))

        runBlocking {
            val own = browser(ServerPin.Key(EcdsaP256.encode(keys.public))).get("$webUrl/login")
            assertEquals(HttpStatusCode.OK, own.status)
            assertEquals("max-age=31536000", own.headers["Strict-Transport-Security"])
            // The identity key does not open the web port any more…
            assertFailsWith<Exception> { browser(identityPin(graph)).get("$webUrl/login") }
        }
        // …and still is what trackers pin on theirs.
        val (invite) = startPairing()
        Tracker().pair(assertNotNull(PairingUri.parse(invite.uri))).successValue()
    }

    @Test
    fun `accounts are managed from the command line, and a new password signs the account out`() {
        val graph = startServer(web = WebSettings(webPort, "127.0.0.1"))

        assertEquals(listOf("ana"), assertIs<AdminReply.Users>(admin.single(AdminRequest.AddUser("Ana", WEB_PASSWORD))).users.map { it.name })
        assertTrue("Ya hay una cuenta" in assertIs<AdminReply.Error>(admin.single(AdminRequest.AddUser("ana", "otra frase bastante larga"))).message)
        assertTrue("15 caracteres" in assertIs<AdminReply.Error>(admin.single(AdminRequest.AddUser("luis", "corta"))).message)

        runBlocking {
            val browser = browser(identityPin(graph))
            val session = browser.signIn()
            assertEquals(HttpStatusCode.OK, browser.page("/", session).status)

            assertIs<AdminReply.Users>(admin.single(AdminRequest.SetPassword("ana", "una frase nueva y bastante larga")))
            assertEquals(HttpStatusCode.SeeOther, browser.page("/", session).status)
            browser.signIn(password = "una frase nueva y bastante larga")

            assertTrue(assertIs<AdminReply.Users>(admin.single(AdminRequest.RemoveUser("ana"))).users.isEmpty())
            assertTrue("No hay ninguna cuenta" in assertIs<AdminReply.Error>(admin.single(AdminRequest.RemoveUser("ana"))).message)
        }
    }

    @Test
    fun `away from home a tracker reaches its server through the relay, whose address it learned at home`() {
        // The server's key exists before the relay's list can name it.
        val serverFingerprint = assertNotNull(keyFingerprint(runBlocking { FileServerKeys(DataDirectory(dir).also { it.prepare() }.identity, Clock.System).publicKey() }))
        val relayKeys = IdentityKeyFile(dir.resolve("relay.p12"), "relay", "PrivateTracker Relay", Instant::now)
        val relay = Relay(RelaySettings("relay.test", 0, "127.0.0.1", setOf(serverFingerprint)), relayKeys.keyManager())
        relay.start()
        try {
            val home = "https://home.lan.test:$port"
            startServer(relay = "relay.test:${relay.port}" to assertNotNull(keyFingerprint(relayKeys.publicKey())), publicUrls = listOf(home))
            val relayUrl = "https://${relayLabel(serverFingerprint)}.relay.test:${relay.port}"
            waitUntil { assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).relayUrl == relayUrl }

            // Set up by hand at home, with the LAN address and the fingerprint: it knows of no relay yet.
            val tracker = Tracker(TrackerConfig(serverUrl = home, deviceName = "Moto de Luis", serverFingerprint = serverFingerprint))
            tracker.recordAndUpload(1)
            assertIs<AdminReply.Done>(admin.single(AdminRequest.Approve(DEVICE_A.value)))
            assertEquals(UploadResult.Completed(sent = 1, rejected = 0, hasMore = false), tracker.upload())
            // The server's signed answer taught it every address, the relay's last.
            assertEquals(listOf(home, relayUrl), runBlocking { tracker.config.get() }.serverAddresses)

            // Away from home the LAN name no longer resolves, and the connections of the home network are gone;
            // the app fails over to the next known address.
            atHome = false
            clients.close()
            assertTrue(tracker.recordAndUpload(2) !is UploadResult.Completed)
            assertTrue(tracker.failOver())

            assertEquals(relayUrl, runBlocking { tracker.config.get() }.serverUrl)
            assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), tracker.upload())
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `two servers pair through the command line and the new one gets the other one's trackers at once`(@TempDir officeDir: Path) {
        startServer()
        val officePort = ServerSocket(0).use { it.localPort }
        val officeGraph = startServer(name = "Oficina", dataDir = officeDir, serverPort = officePort)
        val office = AdminClient(DataDirectory(officeDir).adminSocket)
        val (invite, replies) = startPairing(AdminRequest.PeerInvite)
        val tracker = Tracker()
        val (trackerInvite, _) = startPairing()
        tracker.pair(assertNotNull(PairingUri.parse(trackerInvite.uri))).successValue()
        assertEquals(UploadResult.Completed(sent = 3, rejected = 0, hasMore = false), tracker.recordAndUpload(3))

        // The office joins with the link the home server printed.
        val joined = assertIs<AdminReply.PeerJoined>(office.single(AdminRequest.PeerJoin(invite.uri)))
        assertEquals("Casa" to invite.fingerprint, joined.peer.name to joined.peer.fingerprint)
        assertEquals("Oficina", assertIs<AdminReply.PeerJoined>(replies.poll(10, TimeUnit.SECONDS)).peer.name)

        // The first exchange follows the join at once: the office has the tracker and its positions.
        waitUntil { assertIs<AdminReply.Devices>(office.single(AdminRequest.Devices)).devices.singleOrNull()?.approval == "APPROVED" }
        waitUntil { assertIs<AdminReply.Peers>(office.single(AdminRequest.Peers)).peers.single().lastExchangeAtMillis != null }
        val day = TimeRange(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)))
        assertEquals(3, runBlocking { officeGraph.getDeviceTrack(DEVICE_A, day) }.successValue().pointCount)
        assertNull(assertIs<AdminReply.Peers>(office.single(AdminRequest.Peers)).peers.single().lastError)
        assertEquals(1, assertIs<AdminReply.Status>(admin.single(AdminRequest.Status)).peers)
        assertTrue(assertIs<AdminReply.Peers>(admin.single(AdminRequest.PeerRemove("Oficina"))).peers.isEmpty())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out")
            Thread.sleep(100)
        }
    }

    @Test
    fun `a second server on the same data directory refuses to start`() {
        startServer()

        val second = LinuxServer(ServerGraph(ServeSettings(ServerConfig(port = port + 1, bindAddress = "127.0.0.1"), emptyList()), DataDirectory(dir)), "test")

        assertFailsWith<AlreadyRunningException> { second.start() }
    }
}
