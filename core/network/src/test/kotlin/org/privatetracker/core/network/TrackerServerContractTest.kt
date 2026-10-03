package org.privatetracker.core.network

import io.ktor.server.testing.testApplication
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FixedBattery
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
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
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.PairWithServer
import org.privatetracker.core.domain.usecase.tracker.PairingResult
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.usecase.tracker.VerifyServerIdentity
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
import org.privatetracker.server.api.privateTrackerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tracker use cases talking to the real server module over HTTP with real signatures: the whole
 * path of section 8 of the design (fix, outbox, upload, ingest, acknowledgement) without a phone.
 */
class TrackerServerContractTest {
    private val clock = FakeClock()
    private val serverStore = InMemoryServerStore()
    private val serverConfig = InMemoryServerConfigRepository()
    private val serverKeys = InMemoryServerKeys()
    private val tickets = InMemoryPairingTicketStore()

    private fun serverDependencies(): ServerDependencies {
        val config = serverConfig
        val sessions = SessionTracker(serverStore, SequentialIdGenerator())
        val verifySignature = VerifyRequestSignature(EcdsaP256, InMemoryNonceRegistry(clock::now), clock)
        return ServerDependencies(
            serverVersion = "0.1.0",
            clock = clock,
            serverConfig = config,
            serverKeys = serverKeys,
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

    @Test
    fun `fixes recorded on the tracker wait for approval, then end up stored on the server exactly once`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val keys = InMemoryDeviceKeys()
        val gateway = KtorServerGateway(createClient { expectSuccess = false }, keys, clock)
        val ids = SequentialIdGenerator()
        val outbox = InMemoryOutboxRepository()
        val state = InMemoryTrackerStateRepository()
        val config = InMemoryTrackerConfigRepository(
            TrackerConfig(serverUrl = "http://localhost", deviceName = "Pixel de Ana", batchSize = 2),
        )
        val identity = GetOrCreateDeviceIdentity(InMemoryIdentityRepository(DEVICE_A), ids)
        val record = RecordLocation(outbox, state, config, identity, FixedBattery(), LocationValidator(clock), ids)
        val register = RegisterDevice(gateway, config, state, identity, keys, VerifyServerIdentity(gateway, EcdsaP256), AppInfo(Platform.ANDROID, "0.1.0"), clock)
        val upload = UploadPendingLocations(outbox, gateway, config, state, register, identity, clock)

        assertTrue(TestServerConnection(gateway, clock)("http://localhost").successValue().compatible)
        repeat(5) { assertIs<RecordResult.Recorded>(record(aFix(recordedAt = T0.plusSeconds(it * 10L)))) }

        // New on this server: it registers, then waits with everything still queued.
        assertEquals(UploadResult.RetryLater(DomainError.DevicePendingApproval), upload())
        assertEquals(5, outbox.count())
        val pending = serverStore.get(DEVICE_A)!!
        assertEquals(DeviceApproval.PENDING, pending.approval)
        assertEquals(keyFingerprint(keys.publicKey(DEVICE_A)), keyFingerprint(pending.publicKey!!))

        // The owner compares fingerprints and approves.
        serverStore.update(pending.copy(approval = DeviceApproval.APPROVED))
        assertEquals(UploadResult.Completed(sent = 5, rejected = 0, hasMore = false), upload())

        assertEquals(0, outbox.count())
        assertEquals(5, serverStore.storedLocations.size)
        val device = serverStore.getWithLastLocation(DEVICE_A)!!
        assertEquals("Pixel de Ana", device.device.name)
        assertEquals(T0.plusSeconds(40), device.lastLocation?.recordedAt)
    }

    @Test
    fun `scanning the server's QR pairs the tracker with no typing and no manual approval`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val keys = InMemoryDeviceKeys()
        val gateway = KtorServerGateway(createClient { expectSuccess = false }, keys, clock)
        val ids = SequentialIdGenerator()
        val outbox = InMemoryOutboxRepository()
        val state = InMemoryTrackerStateRepository()
        val config = InMemoryTrackerConfigRepository(TrackerConfig(deviceName = "Pixel de Ana"))
        val identity = GetOrCreateDeviceIdentity(InMemoryIdentityRepository(DEVICE_A), ids)
        val verifyServer = VerifyServerIdentity(gateway, EcdsaP256)
        val register = RegisterDevice(gateway, config, state, identity, keys, verifyServer, AppInfo(Platform.ANDROID, "0.3.0"), clock)
        val upload = UploadPendingLocations(outbox, gateway, config, state, register, identity, clock)
        val record = RecordLocation(outbox, state, config, identity, FixedBattery(), LocationValidator(clock), ids)

        // The server screen shows a QR; the tracker reads its link. The first address leads nowhere.
        val invite = CreatePairingInvite(tickets, serverKeys, serverConfig, clock)(listOf("http://192.0.2.1:8787", "http://localhost"))
            .successValue()
        val scanned = assertNotNull(PairingUri.parse(PairingUri.format(invite)))
        val result = PairWithServer(verifyServer, config, register, clock)(scanned).successValue()

        assertEquals(PairingResult("PrivateTracker Server", "http://localhost", DeviceApproval.APPROVED), result)
        assertEquals(serverKeys.publicKey(), config.get().serverKey)
        repeat(3) { record(aFix(recordedAt = T0.plusSeconds(it * 10L))) }
        assertEquals(UploadResult.Completed(sent = 3, rejected = 0, hasMore = false), upload())
        assertEquals(3, serverStore.storedLocations.size)
    }
}
