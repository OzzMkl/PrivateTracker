package org.privatetracker.tools.simulator

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.io.TempDir
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.TransactionRunner
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.InMemoryEncryptionKeyRepository
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.network.KtorServerGateway
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.core.protocol.crypto.InMemoryEncryptionKeyVault
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
import org.privatetracker.server.api.privateTrackerApi
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The simulator against the real server module, in process: the exit criterion in miniature. */
class SimulationTest {
    @TempDir
    lateinit var dir: Path

    private val store = InMemoryServerStore()
    private val serverKeys = InMemoryServerKeys()
    private val outage = AtomicBoolean(false)

    /** Room runs one write transaction at a time; so does this. */
    private val serialTransactions = object : TransactionRunner {
        private val mutex = Mutex()
        override suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
    }

    private fun serverDependencies(): ServerDependencies {
        val clock = Clock.System
        val config = InMemoryServerConfigRepository()
        val sessions = SessionTracker(store, IdGenerator.Random)
        val verifySignature = VerifyRequestSignature(EcdsaP256, InMemoryNonceRegistry(clock::now), clock)
        return ServerDependencies(
            serverVersion = "0.1.0",
            clock = clock,
            serverConfig = config,
            serverKeys = serverKeys,
            encryptionKeys = EncryptionKeyRing(InMemoryEncryptionKeyRepository(), InMemoryEncryptionKeyVault(), serverKeys, clock),
            registerOrUpdateDevice = RegisterOrUpdateDevice(
                store, config, sessions, EcdsaP256, verifySignature, InMemoryPairingTicketStore(), serialTransactions, clock,
            ),
            authenticateDevice = AuthenticateDevice(store, verifySignature),
            ingestLocationBatch = IngestLocationBatch(
                store, store, store, config, sessions, LocationValidator(clock), serialTransactions, clock,
            ),
            getDeviceOverviews = GetDeviceOverviews(store, config, clock),
            getDeviceDetail = GetDeviceDetail(store, store, config, clock),
            requestsPerMinute = 100_000,
            isShuttingDown = { outage.get() },
        )
    }

    private suspend fun options(faults: FaultPlan = FaultPlan()) = SimulationOptions(
        serverUrl = "https://localhost",
        serverFingerprint = keyFingerprint(serverKeys.publicKey())!!,
        trackers = 10,
        interval = Duration.ofMillis(20),
        duration = Duration.ofSeconds(2),
        batchSize = 5,
        faults = faults,
        retry = RetryPolicy(initial = Duration.ofMillis(10), max = Duration.ofMillis(50)),
        drainTimeout = Duration.ofSeconds(20),
        reportEvery = Duration.ofSeconds(1),
        outDir = dir.resolve("run"),
    )

    /** The owner approving each simulated phone shortly after it asks, as a person would in the app. */
    private fun CoroutineScope.approveNewDevices() = launch {
        while (isActive) {
            delay(100)
            store.getAllWithLastLocation().map { it.device }.filter { it.approval == DeviceApproval.PENDING }
                .forEach { store.update(it.copy(approval = DeviceApproval.APPROVED)) }
        }
    }

    private fun InMemoryServerStore.snapshot() = ServerSnapshot { deviceIds ->
        storedLocations.map { it.location }.filter { it.deviceId.value in deviceIds }.map {
            LocationRecord(LocationKey(it.deviceId.value, it.id.value), it.recordedAt.toEpochMilli(), it.latitude, it.longitude)
        }
    }

    @Test
    fun `ten trackers on a flaky network with a server outage lose nothing`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        // Faults this heavy would stall the final drain if they applied to it; they must not.
        val options = options(FaultPlan(dropRate = 0.3, lostAckRate = 0.3))
        val simulation = Simulation(
            options = options,
            gatewayFactory = { keys ->
                // The test client has no TLS; the fingerprint still has to match the key health proves.
                val client = createClient { expectSuccess = false }
                KtorServerGateway({ client }, keys)
            },
            out = PrintStream(OutputStream.nullOutputStream()),
        )

        val result = coroutineScope {
            val approvals = approveNewDevices()
            // The server answers 503 for a while, as while its phone stops the service.
            launch {
                delay(500)
                outage.set(true)
                delay(400)
                outage.set(false)
            }
            simulation.run().also { approvals.cancel() }
        }

        assertTrue(result.trackerSideClean, result.toString())
        assertTrue(result.generated > 300, "only ${result.generated} generated")
        assertEquals(result.generated, result.acknowledged)
        assertTrue(result.duplicates > 0, "lost answers must come back as duplicates")
        assertTrue("HTTP_503" in result.failures, "the outage must have been hit: ${result.failures}")
        assertTrue("DEVICE_PENDING_APPROVAL" in result.failures, "positions must have waited for approval: ${result.failures}")
        assertTrue(result.injectedDrops > 0 && result.injectedLostAcks > 0)

        val ledger = Ledger.read(options.outDir.resolve(Ledger.FILE_NAME))
        val end = assertNotNull(ledger.end)
        assertTrue(end.drained && !end.stoppedEarly, end.toString())
        val report = Verifier.verify(ledger, store.snapshot())
        assertTrue(report.passed, Verifier.format(report))
        assertEquals(10, report.devices)
        assertEquals(result.generated.toInt(), report.delivered)
        assertEquals(result.generated.toInt(), store.storedLocations.size)
    }

    @Test
    fun `a whole history goes up in full batches, oldest first, and verifies against the server`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val options = options().copy(trackers = 2, interval = Duration.ofMinutes(10), batchSize = 100, history = Duration.ofDays(3))
        val simulation = Simulation(
            options = options,
            gatewayFactory = { keys ->
                val client = createClient { expectSuccess = false }
                KtorServerGateway({ client }, keys)
            },
            out = PrintStream(OutputStream.nullOutputStream()),
        )

        val result = coroutineScope {
            val approvals = approveNewDevices()
            simulation.run().also { approvals.cancel() }
        }

        // Three days at one position every ten minutes, per tracker.
        assertEquals(2L * 3 * 24 * 6, result.generated)
        assertTrue(result.trackerSideClean, result.toString())
        val stored = store.storedLocations.map { it.location }
        assertTrue(stored.minOf { it.recordedAt } < Instant.now().minus(Duration.ofDays(2)), "the history must reach back days")
        val report = Verifier.verify(Ledger.read(options.outDir.resolve(Ledger.FILE_NAME)), store.snapshot())
        assertTrue(report.passed, Verifier.format(report))
    }

    @Test
    fun `a stop request ends the run early and still drains every outbox`() = testApplication {
        application { privateTrackerApi(serverDependencies()) }
        val options = options().copy(duration = Duration.ofHours(1))
        val stop = CompletableDeferred<Unit>()
        val simulation = Simulation(
            options = options,
            gatewayFactory = { keys ->
                // The test client has no TLS; the fingerprint still has to match the key health proves.
                val client = createClient { expectSuccess = false }
                KtorServerGateway({ client }, keys)
            },
            out = PrintStream(OutputStream.nullOutputStream()),
        )

        val result = coroutineScope {
            val approvals = approveNewDevices()
            // Stop once the run is clearly under way; a fixed delay could fire before a slow start.
            launch {
                while (store.storedLocations.size < 20) delay(20)
                stop.complete(Unit)
            }
            simulation.run(stop).also { approvals.cancel() }
        }

        assertTrue(result.stoppedEarly, result.toString())
        assertTrue(result.elapsed < Duration.ofSeconds(30), result.toString())
        assertTrue(result.trackerSideClean, result.toString())
        val report = Verifier.verify(Ledger.read(options.outDir.resolve(Ledger.FILE_NAME)), store.snapshot())
        assertTrue(report.passed, Verifier.format(report))
    }
}
