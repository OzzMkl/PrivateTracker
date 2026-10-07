package org.privatetracker.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headers
import io.ktor.server.testing.testApplication
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FixedBattery
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
import org.privatetracker.core.domain.testing.InMemoryEncryptionKeyRepository
import org.privatetracker.core.domain.testing.InMemoryIdentityRepository
import org.privatetracker.core.domain.testing.InMemoryOutboxRepository
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.InMemoryTrackerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerStateRepository
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aFix
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.usecase.tracker.GetServerEncryptionKey
import org.privatetracker.core.domain.usecase.tracker.PairWithServer
import org.privatetracker.core.domain.usecase.tracker.PairingResult
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.usecase.tracker.VerifyServerIdentity
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.crypto.InMemoryEncryptionKeyVault
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.core.protocol.v1.SealedBodies
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
import org.privatetracker.server.api.privateTrackerApi
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tracker use cases talking to the real server module over HTTP with real signatures and real
 * encryption: the whole path of section 8 of the design (fix, outbox, upload, ingest, acknowledgement)
 * without a phone.
 */
class TrackerServerContractTest {
    private val clock = FakeClock()
    private val serverStore = InMemoryServerStore()
    private val serverConfig = InMemoryServerConfigRepository()
    private val serverKeys = InMemoryServerKeys()
    private val encryptionKeys = EncryptionKeyRing(InMemoryEncryptionKeyRepository(), InMemoryEncryptionKeyVault(), serverKeys, clock)
    private val tickets = InMemoryPairingTicketStore()

    private fun serverDependencies(): ServerDependencies {
        val config = serverConfig
        val sessions = SessionTracker(serverStore, SequentialIdGenerator())
        val verifySignature = VerifyRequestSignature(EcdsaP256, InMemoryNonceRegistry(clock::now), clock)
        return ServerDependencies(
            serverVersion = "0.5.0",
            clock = clock,
            serverConfig = config,
            serverKeys = serverKeys,
            encryptionKeys = encryptionKeys,
            registerOrUpdateDevice = RegisterOrUpdateDevice(
                serverStore, config, sessions, EcdsaP256, verifySignature, tickets, ImmediateTransactionRunner, clock,
            ),
            authenticateDevice = AuthenticateDevice(serverStore, verifySignature),
            ingestLocationBatch = IngestLocationBatch(
                serverStore, serverStore, serverStore, config, sessions, LocationValidator(clock), ImmediateTransactionRunner, clock,
            ),
            getDeviceOverviews = GetDeviceOverviews(serverStore, config, clock),
            getDeviceDetail = GetDeviceDetail(serverStore, serverStore, config, clock),
        )
    }

    /** One phone in Tracker mode, with the app's own use cases over in-memory storage; [keys] also sign for [gateway]. */
    private inner class Tracker(val keys: InMemoryDeviceKeys, gateway: ServerGateway, config: TrackerConfig) {
        val ids = SequentialIdGenerator()
        val outbox = InMemoryOutboxRepository()
        val state = InMemoryTrackerStateRepository()
        val config = InMemoryTrackerConfigRepository(config)
        val identity = GetOrCreateDeviceIdentity(InMemoryIdentityRepository(DEVICE_A), ids)
        val record = RecordLocation(outbox, state, this.config, identity, FixedBattery(), LocationValidator(clock), ids)
        val verifyServer = VerifyServerIdentity(gateway, EcdsaP256)
        val encryption = GetServerEncryptionKey(state, verifyServer, clock)
        val register = RegisterDevice(gateway, this.config, state, identity, keys, verifyServer, encryption, AppInfo(Platform.ANDROID, "0.5.0"), clock)
        val upload = UploadPendingLocations(outbox, gateway, this.config, state, register, encryption, identity, clock)
        val pair = PairWithServer(verifyServer, this.config, register, clock)
    }

    @Test
    fun `a server typed in by hand with its fingerprint gets the fixes once its owner approves the tracker`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        // The test client has no TLS; the pin still decides whether health proves the right key.
        val client = createClient { expectSuccess = false }
        val keys = InMemoryDeviceKeys()
        val gateway = KtorServerGateway({ client }, keys, clock)
        val fingerprint = assertNotNull(keyFingerprint(serverKeys.publicKey()))
        val tracker = Tracker(
            keys,
            gateway,
            TrackerConfig(serverUrl = "https://localhost", deviceName = "Pixel de Ana", batchSize = 2, serverFingerprint = fingerprint),
        )

        assertTrue(TestServerConnection(gateway, clock)("https://localhost", fingerprint).successValue().compatible)
        repeat(5) { assertIs<RecordResult.Recorded>(tracker.record(aFix(recordedAt = T0.plusSeconds(it * 10L)))) }

        // New on this server: it registers, then waits with everything still queued.
        assertEquals(UploadResult.RetryLater(DomainError.DevicePendingApproval), tracker.upload())
        assertEquals(5, tracker.outbox.count())
        val pending = serverStore.get(DEVICE_A)!!
        assertEquals(DeviceApproval.PENDING, pending.approval)
        assertEquals(keyFingerprint(keys.publicKey(DEVICE_A)), keyFingerprint(pending.publicKey!!))
        // Registering proved the server's key against the fingerprint, and the tracker kept the whole key.
        assertEquals(serverKeys.publicKey(), tracker.config.get().serverKey)

        // The owner compares fingerprints and approves.
        serverStore.update(pending.copy(approval = DeviceApproval.APPROVED))
        assertEquals(UploadResult.Completed(sent = 5, rejected = 0, hasMore = false), tracker.upload())

        assertEquals(0, tracker.outbox.count())
        assertEquals(5, serverStore.storedLocations.size)
        val device = serverStore.getWithLastLocation(DEVICE_A)!!
        assertEquals("Pixel de Ana", device.device.name)
        assertEquals(T0.plusSeconds(40), device.lastLocation?.recordedAt)
    }

    @Test
    fun `scanning the server's QR pairs the tracker with no typing and no manual approval`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val client = createClient { expectSuccess = false }
        val keys = InMemoryDeviceKeys()
        val tracker = Tracker(keys, KtorServerGateway({ client }, keys, clock), TrackerConfig(deviceName = "Pixel de Ana"))

        // The server screen shows a QR; the tracker reads its link. The first address leads nowhere.
        val invite = CreatePairingInvite(tickets, serverKeys, serverConfig, clock)(listOf("https://192.0.2.1:8787", "https://localhost"))
            .successValue()
        val scanned = assertNotNull(PairingUri.parse(PairingUri.format(invite)))
        val result = tracker.pair(scanned).successValue()

        assertEquals(PairingResult("PrivateTracker Server", "https://localhost", DeviceApproval.APPROVED), result)
        assertEquals(serverKeys.publicKey(), tracker.config.get().serverKey)
        repeat(3) { tracker.record(aFix(recordedAt = T0.plusSeconds(it * 10L))) }
        assertEquals(UploadResult.Completed(sent = 3, rejected = 0, hasMore = false), tracker.upload())
        assertEquals(3, serverStore.storedLocations.size)
    }

    @Test
    fun `a relay between tracker and server sees only sealed data, from pairing to acknowledgment`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val relay = Relay(createClient { expectSuccess = false })
        val keys = InMemoryDeviceKeys()
        val tracker = Tracker(keys, KtorServerGateway({ relay.client }, keys, clock), TrackerConfig(deviceName = "Pixel de Ana"))
        val invite = CreatePairingInvite(tickets, serverKeys, serverConfig, clock)(listOf("https://localhost")).successValue()

        tracker.pair(invite).successValue()
        repeat(4) { tracker.record(aFix(latitude = 19.4326 + it / 1000.0, longitude = -99.1332, recordedAt = T0.plusSeconds(it * 10L))) }
        assertEquals(UploadResult.Completed(sent = 4, rejected = 0, hasMore = false), tracker.upload())

        // Everything arrived, and nothing of it crossed the relay readable.
        assertEquals(4, serverStore.storedLocations.size)
        val secrets = buildList {
            add("Pixel de Ana")
            add(keys.publicKey(DEVICE_A))
            add(invite.ticketId)
            add("19.43")
            add("-99.13")
            add("recorded_at")
            // The fixes' times; health's own server time is public.
            addAll(listOf("18:00:10Z", "18:00:20Z", "18:00:30Z"))
            addAll(serverStore.storedLocations.map { it.location.id.value })
        }
        val bodies = relay.bodies.joinToString("\n")
        secrets.forEach { secret -> assertTrue(secret !in bodies, "the relay read \"$secret\"") }
        // Device routes went through it, sealed both ways.
        val sealed = relay.exchanges.filter { (path, _) -> !path.endsWith("/health") }
        assertEquals(listOf("/api/v1/devices/register", "/api/v1/devices/${DEVICE_A.value}/locations"), sealed.map { it.first })
        sealed.forEach { (_, bodies) -> bodies.forEach { assertTrue(it.contains("\"ciphertext\":"), it) } }
    }

    @Test
    fun `weekly and on-demand rotations lose no position, and an old key opens nothing once deleted`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val relay = Relay(createClient { expectSuccess = false })
        val keys = InMemoryDeviceKeys()
        val tracker = Tracker(keys, KtorServerGateway({ relay.client }, keys, clock), TrackerConfig(deviceName = "Pixel de Ana"))
        val invite = CreatePairingInvite(tickets, serverKeys, serverConfig, clock)(listOf("https://localhost")).successValue()
        tracker.pair(invite).successValue()
        suspend fun recordAndUpload(count: Int): UploadResult {
            repeat(count) { tracker.record(aFix(recordedAt = clock.now())).also { clock.advanceBy(Duration.ofSeconds(1)) } }
            return tracker.upload()
        }

        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), recordAndUpload(2))
        val first = assertNotNull(tracker.state.encryptionKey).key
        val capturedUnderFirst = relay.lastLocationsRequest()

        // A week later the server offers a new key, and the tracker takes it when its own copy ends.
        clock.current = first.useUntil.minusSeconds(30)
        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), recordAndUpload(2))
        assertEquals(first, tracker.state.encryptionKey?.key)
        clock.current = first.useUntil.plusSeconds(1)
        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), recordAndUpload(2))
        val second = assertNotNull(tracker.state.encryptionKey).key
        assertTrue(second.id != first.id)

        // The owner rotates now: the tracker is told its key is gone, fetches the new one, and resends.
        encryptionKeys.rotate()
        assertEquals(UploadResult.Completed(sent = 3, rejected = 0, hasMore = false), recordAndUpload(3))
        assertTrue(tracker.state.encryptionKey?.key?.id !in setOf(first.id, second.id))
        assertEquals(9, serverStore.storedLocations.size)

        // A request captured under the first key no longer opens anywhere, not even on the server: its key was deleted.
        val reopened = SealedBodies.open(capturedUnderFirst.body.toByteArray(), "POST", capturedUnderFirst.url.encodedPath) { id ->
            encryptionKeys.decryptionKey(id)?.let { it.privateKey to it.key.publicKey }
        }
        assertEquals(SealedBodies.OpenResult.UnknownKey(first.id), reopened)
    }

    /**
     * Stands between tracker and server, as a relay or a proxy that ends TLS would: it passes every
     * request and answer on unchanged, and keeps whatever bodies it saw on the way.
     */
    private class Relay(upstream: HttpClient) {
        val exchanges = mutableListOf<Pair<String, List<String>>>()
        val bodies: List<String> get() = exchanges.flatMap { it.second }
        private val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()

        val client = createProtocolHttpClient(
            MockEngine { request ->
                requests += request
                val body = request.body.toByteArray()
                val answer = upstream.request(request.url) {
                    method = request.method
                    // The body brings its own type and length.
                    request.headers.forEach { name, values -> if (name !in BODY_HEADERS) headers.appendAll(name, values) }
                    if (body.isNotEmpty()) setBody(ByteArrayContent(body, request.body.contentType))
                }
                val answerBody = answer.bodyAsBytes()
                exchanges += request.url.encodedPath to listOf(body.decodeToString(), answerBody.decodeToString())
                val passed = headers { answer.headers.forEach { name, values -> if (name !in LENGTH_HEADERS) appendAll(name, values) } }
                respond(answerBody, answer.status, passed)
            },
            "PrivateTracker-Tracker/0.5.0",
        )

        fun lastLocationsRequest() = requests.last { it.url.encodedPath.endsWith("/locations") }
    }

    private companion object {
        val LENGTH_HEADERS = setOf(HttpHeaders.ContentLength, HttpHeaders.TransferEncoding)
        val BODY_HEADERS = LENGTH_HEADERS + HttpHeaders.ContentType
    }
}
