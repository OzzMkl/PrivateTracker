package org.privatetracker.tools.simulator

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.LocationId
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.usecase.tracker.RecordLocation
import org.privatetracker.core.domain.usecase.tracker.RegisterDevice
import org.privatetracker.core.domain.usecase.tracker.UploadPendingLocations
import org.privatetracker.core.domain.usecase.tracker.VerifyServerIdentity
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.protocol.crypto.EcdsaP256
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

const val SIMULATOR_VERSION = "0.1.0"

/**
 * One phone in Tracker mode: the app's own capture and upload use cases over an in-memory outbox,
 * fed by a [RandomRoute] instead of GPS. As in UploadCoordinator, one upload runs at a time and each
 * fix starts one, which also retries whatever an earlier failure left in the outbox.
 */
class SimulatedTracker(
    val deviceId: DeviceId,
    val name: String,
    private val options: SimulationOptions,
    gateway: ServerGateway,
    keys: DeviceKeys,
    private val ledger: Ledger,
    private val random: Random,
    private val clock: Clock = Clock.System,
    ids: IdGenerator = IdGenerator.Random,
) {
    val stats = TrackerStats()

    private val route = RandomRoute(options.center, Random(random.nextLong()))
    private val outbox = MemoryOutbox { dropped ->
        stats.dropped.addAndGet(dropped.size.toLong())
        ledger.dropped(dropped)
    }
    private val config = MemoryTrackerConfig(
        TrackerConfig(
            serverUrl = options.serverUrl,
            serverFingerprint = options.serverFingerprint,
            deviceName = name,
            intervalSeconds = options.interval.seconds.toInt().coerceAtLeast(1),
            batchSize = options.batchSize,
            maxQueueSize = options.maxQueueSize,
            trackingEnabled = true,
        ),
    )
    private val state = MemoryTrackerState()
    private val identity = GetOrCreateDeviceIdentity(FixedIdentity(deviceId), ids)
    private val network = FaultInjectingGateway(RecordingGateway(gateway, ledger, stats), options.faults, Random(random.nextLong()), stats)
    private val record = RecordLocation(outbox, state, config, identity, SimulatedBattery(Random(random.nextLong())), LocationValidator(clock), ids)
    private val upload = UploadPendingLocations(
        outbox = outbox,
        gateway = network,
        trackerConfig = config,
        trackerState = state,
        registerDevice = RegisterDevice(
            network, config, state, identity, keys, VerifyServerIdentity(network, EcdsaP256), AppInfo(Platform.OTHER, SIMULATOR_VERSION), clock,
        ),
        identity = identity,
        clock = clock,
    )
    private val uploadLock = Mutex()

    suspend fun queued(): Int = outbox.count()

    /** Records a fix every interval and uploads after each one, until cancelled. */
    suspend fun run() = coroutineScope {
        // Real phones are not in step; spreading the first fix spreads every later one too.
        delay(random.nextLong(0, options.interval.toMillis().coerceAtLeast(1)))
        while (isActive) {
            when (val result = record(route.next(clock.now()))) {
                is RecordResult.Recorded -> {
                    ledger.generated(result.location)
                    stats.generated.incrementAndGet()
                }
                is RecordResult.Skipped -> stats.skipped.incrementAndGet()
            }
            if (!uploadLock.isLocked) launch { uploadNow() }
            delay(options.interval.toMillis())
        }
    }

    /** Uploads until the outbox is empty or a failure stops the round; never two rounds at once. */
    suspend fun uploadNow(): UploadResult = uploadLock.withLock {
        var result: UploadResult
        do {
            result = upload()
            stats.record(result)
        } while (result is UploadResult.Completed && result.hasMore)
        result
    }

    /**
     * After the run: no more fixes and no more injected faults, only uploads with backoff until the
     * outbox is empty. False on timeout.
     */
    suspend fun drain(timeout: Duration): Boolean = withTimeoutOrNull(timeout.toMillis()) {
        network.enabled = false
        var failures = 0
        while (outbox.count() > 0) {
            when (val result = uploadNow()) {
                is UploadResult.Completed -> failures = 0
                is UploadResult.RetryLater -> delay(options.retry.delayFor(++failures, result.retryAfterSeconds).toMillis())
                is UploadResult.Blocked -> delay(options.retry.delayFor(++failures, null).toMillis())
            }
        }
        true
    } ?: false
}

/** Counters of one tracker, read by the progress report while the tracker runs. */
class TrackerStats {
    val generated = AtomicLong()
    val skipped = AtomicLong()
    val accepted = AtomicLong()
    val duplicates = AtomicLong()
    val rejected = AtomicLong()
    val dropped = AtomicLong()
    val injectedDrops = AtomicLong()
    val injectedLostAcks = AtomicLong()

    /** Locations the server said it holds, each counted once however many times it answered. */
    private val acknowledged: MutableSet<LocationId> = ConcurrentHashMap.newKeySet()
    private val failures = ConcurrentHashMap<String, AtomicLong>()
    private val latenciesMs = mutableListOf<Long>()

    val acknowledgedCount: Int get() = acknowledged.size

    fun answered(result: LocationBatchResult, latencyMs: Long) {
        accepted.addAndGet(result.accepted.size.toLong())
        duplicates.addAndGet(result.duplicates.size.toLong())
        rejected.addAndGet(result.rejected.size.toLong())
        acknowledged += result.accepted
        acknowledged += result.duplicates
        synchronized(latenciesMs) { latenciesMs += latencyMs }
    }

    /** True while the server says the owner has not approved this device yet. */
    @Volatile
    var waitingForApproval = false
        private set

    fun record(result: UploadResult) {
        val error = when (result) {
            is UploadResult.Completed -> null
            is UploadResult.RetryLater -> result.error
            is UploadResult.Blocked -> result.error
        }
        waitingForApproval = error == DomainError.DevicePendingApproval
        if (error != null) failures.getOrPut(error.code) { AtomicLong() }.incrementAndGet()
    }

    fun failuresByCode(): Map<String, Long> = failures.mapValues { it.value.get() }

    fun latencies(): List<Long> = synchronized(latenciesMs) { latenciesMs.toList() }
}
