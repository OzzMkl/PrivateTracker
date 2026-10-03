package org.privatetracker.core.network

import io.ktor.server.testing.testApplication
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.UploadResult
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
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.privateTrackerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The tracker use cases talking to the real server module over HTTP: the whole path of section 8
 * of the design (fix, outbox, upload, ingest, acknowledgement) without a phone.
 */
class TrackerServerContractTest {
    private val clock = FakeClock()
    private val serverStore = InMemoryServerStore()

    private fun serverDependencies(): ServerDependencies {
        val config = InMemoryServerConfigRepository()
        val sessions = SessionTracker(serverStore, SequentialIdGenerator())
        return ServerDependencies(
            serverVersion = "0.1.0",
            clock = clock,
            serverConfig = config,
            registerOrUpdateDevice = RegisterOrUpdateDevice(serverStore, config, sessions, ImmediateTransactionRunner, clock),
            ingestLocationBatch = IngestLocationBatch(
                serverStore, serverStore, serverStore, config, sessions, LocationValidator(clock), ImmediateTransactionRunner, clock,
            ),
            getDeviceOverviews = GetDeviceOverviews(serverStore, config, clock),
            getDeviceDetail = GetDeviceDetail(serverStore, serverStore, config, clock),
        )
    }

    @Test
    fun `fixes recorded on the tracker end up stored on the server exactly once`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val gateway = KtorServerGateway(createClient { expectSuccess = false })
        val ids = SequentialIdGenerator()
        val outbox = InMemoryOutboxRepository()
        val state = InMemoryTrackerStateRepository()
        val config = InMemoryTrackerConfigRepository(
            TrackerConfig(serverUrl = "http://localhost", deviceName = "Pixel de Ana", batchSize = 2),
        )
        val identity = GetOrCreateDeviceIdentity(InMemoryIdentityRepository(DEVICE_A), ids)
        val record = RecordLocation(outbox, state, config, identity, FixedBattery(), LocationValidator(clock), ids)
        val register = RegisterDevice(gateway, config, state, identity, AppInfo(Platform.ANDROID, "0.1.0"), clock)
        val upload = UploadPendingLocations(outbox, gateway, config, state, register, identity, clock)

        assertTrue(TestServerConnection(gateway, clock)("http://localhost").successValue().compatible)
        repeat(5) { assertIs<RecordResult.Recorded>(record(aFix(recordedAt = T0.plusSeconds(it * 10L)))) }

        assertEquals(UploadResult.Completed(sent = 5, rejected = 0, hasMore = false), upload())

        assertEquals(0, outbox.count())
        assertEquals(5, serverStore.storedLocations.size)
        val device = serverStore.getWithLastLocation(DEVICE_A)!!
        assertEquals("Pixel de Ana", device.device.name)
        assertEquals(T0.plusSeconds(40), device.lastLocation?.recordedAt)
    }
}
