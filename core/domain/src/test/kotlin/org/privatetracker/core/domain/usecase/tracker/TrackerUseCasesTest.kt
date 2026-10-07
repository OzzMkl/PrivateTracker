package org.privatetracker.core.domain.usecase.tracker

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.AppInfo
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DiscoveredServer
import org.privatetracker.core.domain.model.LocationBatchResult
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Platform
import org.privatetracker.core.domain.model.RecordResult
import org.privatetracker.core.domain.model.RejectedLocation
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.model.ServerPin
import org.privatetracker.core.domain.model.SkipReason
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackerRegistration
import org.privatetracker.core.domain.model.UploadResult
import org.privatetracker.core.domain.model.isValidPairingProof
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.serverKeyHint
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FakeDeviceKeys
import org.privatetracker.core.domain.testing.FakeServerDiscovery
import org.privatetracker.core.domain.testing.FakeServerGateway
import org.privatetracker.core.domain.testing.FakeServerKeys
import org.privatetracker.core.domain.testing.FakeSignatureVerifier
import org.privatetracker.core.domain.testing.FixedBattery
import org.privatetracker.core.domain.testing.InMemoryIdentityRepository
import org.privatetracker.core.domain.testing.InMemoryOutboxRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerStateRepository
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aFix
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.fakePublicKey
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.common.GetOrCreateDeviceIdentity
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

private const val SERVER = "https://192.168.1.10:8787"
private val SERVER_FINGERPRINT = keyFingerprint(FakeServerKeys.SERVER_KEY)!!

/** Wires the tracker use cases over in-memory storage and a scriptable server. */
private class TrackerFixture(
    config: TrackerConfig = TrackerConfig(serverUrl = SERVER, deviceName = "Pixel de Ana", serverKey = FakeServerKeys.SERVER_KEY),
) {
    val clock = FakeClock()
    val outbox = InMemoryOutboxRepository()
    val state = InMemoryTrackerStateRepository()
    val config = InMemoryTrackerConfigRepository(config)
    val gateway = FakeServerGateway(clock)
    val ids = SequentialIdGenerator()
    val identity = GetOrCreateDeviceIdentity(InMemoryIdentityRepository(DEVICE_A), ids)
    val record = RecordLocation(outbox, state, this.config, identity, FixedBattery(55), LocationValidator(clock), ids)
    val keys = FakeDeviceKeys()
    val verifyServer = VerifyServerIdentity(gateway, FakeSignatureVerifier)
    val register = RegisterDevice(gateway, this.config, state, identity, keys, verifyServer, AppInfo(Platform.ANDROID, "0.1.0"), clock)
    val upload = UploadPendingLocations(outbox, gateway, this.config, state, register, identity, clock)
    val pair = PairWithServer(verifyServer, this.config, register, clock)
    val discovery = FakeServerDiscovery()
    val failOver = FailOverServerAddress(this.config, verifyServer, discovery, clock)

    suspend fun enqueue(count: Int) = repeat(count) { outbox.enqueue(aLocation(it + 1), maxSize = 10_000) }
}

class RecordLocationTest {
    @Test
    fun `a fix becomes a location in the outbox with a fresh id and the battery level`() = runTest {
        val fixture = TrackerFixture()

        val recorded = assertIs<RecordResult.Recorded>(fixture.record(aFix()))

        assertEquals(DEVICE_A, recorded.location.deviceId)
        assertEquals(55, recorded.location.batteryPct)
        assertEquals(listOf(recorded.location), fixture.outbox.pending.map { it.location })
        assertEquals(recorded.location, fixture.state.lastRecorded)
    }

    @Test
    fun `fixes less precise than the configured limit are skipped`() = runTest {
        val fixture = TrackerFixture()

        assertEquals(RecordResult.Skipped(SkipReason.LOW_ACCURACY), fixture.record(aFix(accuracyM = 150f)))
        assertTrue(fixture.outbox.pending.isEmpty())
    }

    @Test
    fun `with a minimum distance, fixes too close to the last one are skipped`() = runTest {
        val fixture = TrackerFixture()
        fixture.config.update { it.copy(minDistanceM = 50f) }
        fixture.record(aFix(latitude = 19.4326))

        // 0.0001 degrees of latitude is about 11 m; 0.001 is about 111 m.
        assertEquals(RecordResult.Skipped(SkipReason.TOO_CLOSE), fixture.record(aFix(latitude = 19.4327)))
        assertIs<RecordResult.Recorded>(fixture.record(aFix(latitude = 19.4336)))
    }

    @Test
    fun `a full queue drops its oldest location`() = runTest {
        val fixture = TrackerFixture()
        fixture.config.update { it.copy(maxQueueSize = 2) }
        val first = assertIs<RecordResult.Recorded>(fixture.record(aFix(recordedAt = T0))).location
        fixture.record(aFix(recordedAt = T0.plusSeconds(60)))

        val third = assertIs<RecordResult.Recorded>(fixture.record(aFix(recordedAt = T0.plusSeconds(120))))

        assertEquals(1, third.droppedOldest)
        assertTrue(fixture.outbox.pending.none { it.location.id == first.id })
    }
}

class UploadPendingLocationsTest {
    @Test
    fun `without a server URL the upload is blocked`() = runTest {
        val fixture = TrackerFixture(TrackerConfig())

        assertEquals(UploadResult.Blocked(DomainError.NotConfigured), fixture.upload())
    }

    @Test
    fun `an empty outbox completes without touching the network`() = runTest {
        val fixture = TrackerFixture()

        assertEquals(UploadResult.Completed(sent = 0, rejected = 0, hasMore = false), fixture.upload())
        assertTrue(fixture.gateway.registrations.isEmpty())
    }

    @Test
    fun `registers once, then drains the outbox in batches of the smaller limit`() = runTest {
        val fixture = TrackerFixture()
        fixture.config.update { it.copy(batchSize = 50) }
        fixture.gateway.maxBatchSize = 2
        fixture.enqueue(5)

        assertEquals(UploadResult.Completed(sent = 5, rejected = 0, hasMore = false), fixture.upload())

        assertEquals(1, fixture.gateway.registrations.size)
        assertEquals(listOf(2, 2, 1), fixture.gateway.uploads.map { it.size })
        assertTrue(fixture.outbox.pending.isEmpty())
        assertEquals(SERVER, fixture.state.registration?.serverUrl)
    }

    @Test
    fun `a network failure keeps the outbox intact and asks for a retry`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(2)
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.Network.Unreachable) }

        val result = fixture.upload()

        assertEquals(UploadResult.RetryLater(DomainError.Network.Unreachable), result)
        assertEquals(listOf(1, 1), fixture.outbox.pending.map { it.attempts })
        assertTrue(fixture.outbox.pending.all { it.lastError == "NETWORK_UNREACHABLE" && it.lastAttemptAt == T0 })
    }

    @Test
    fun `rate limiting is retried after the time the server asked for`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(1)
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.Http(429, "RATE_LIMITED", 30)) }

        val result = assertIs<UploadResult.RetryLater>(fixture.upload())

        assertEquals(30, result.retryAfterSeconds)
    }

    @Test
    fun `client errors block the upload because retrying cannot help`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(1)
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.Http(415)) }

        assertEquals(UploadResult.Blocked(DomainError.Http(415)), fixture.upload())
        assertEquals(1, fixture.outbox.pending.size)
    }

    @Test
    fun `a server that forgot the device gets a new registration and the batch is resent`() = runTest {
        val fixture = TrackerFixture()
        fixture.state.registration = TrackerRegistration(SERVER, 100, T0)
        fixture.enqueue(2)
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.DeviceNotRegistered) }

        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), fixture.upload())

        assertEquals(1, fixture.gateway.registrations.size)
        assertEquals(2, fixture.gateway.uploads.size)
    }

    @Test
    fun `a batch the server finds too large is halved`() = runTest {
        val fixture = TrackerFixture()
        fixture.config.update { it.copy(batchSize = 4) }
        fixture.enqueue(4)
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.BatchTooLarge(2)) }

        fixture.upload()

        assertEquals(listOf(4, 2, 2), fixture.gateway.uploads.map { it.size })
        assertTrue(fixture.outbox.pending.isEmpty())
    }

    @Test
    fun `rejected locations leave the outbox, unacknowledged ones stay`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(3)
        fixture.gateway.uploadResponses += { batch ->
            Outcome.Success(
                LocationBatchResult(
                    accepted = listOf(batch[0].id),
                    duplicates = emptyList(),
                    rejected = listOf(RejectedLocation(batch[1].id.value, RejectionReason.INVALID_COORDINATES, "")),
                    serverTime = T0,
                ),
            )
        }

        val result = fixture.upload(maxBatches = 1)

        assertEquals(UploadResult.Completed(sent = 1, rejected = 1, hasMore = true), result)
        assertEquals(listOf(aLocation(3).id), fixture.outbox.pending.map { it.location.id })
    }

    @Test
    fun `registration introduces this device's public key`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(1)

        fixture.upload()

        assertEquals(fakePublicKey(DEVICE_A), fixture.gateway.registrations.single().publicKey)
    }

    @Test
    fun `a device waiting for approval keeps its outbox and tries again later`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(2)
        fixture.gateway.approval = DeviceApproval.PENDING
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.DevicePendingApproval) }

        assertEquals(UploadResult.RetryLater(DomainError.DevicePendingApproval), fixture.upload())
        assertEquals(2, fixture.outbox.pending.size)

        // Approved meanwhile: the next attempt goes through without registering again.
        assertEquals(UploadResult.Completed(sent = 2, rejected = 0, hasMore = false), fixture.upload())
        assertEquals(1, fixture.gateway.registrations.size)
    }

    @Test
    fun `a rejected device or a refused key blocks uploads, keeping the outbox`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(1)
        val keyMismatch = DomainError.AuthenticationFailed(AuthFailure.KEY_MISMATCH)
        fixture.gateway.uploadResponses += { Outcome.Failure(DomainError.DeviceRejected) }
        fixture.gateway.uploadResponses += { Outcome.Failure(keyMismatch) }

        assertEquals(UploadResult.Blocked(DomainError.DeviceRejected), fixture.upload())
        assertEquals(UploadResult.Blocked(keyMismatch), fixture.upload())
        assertEquals(1, fixture.outbox.pending.size)
    }

    @Test
    fun `a broken key store blocks uploads as such, keeping the outbox`() = runTest {
        val fixture = TrackerFixture()
        fixture.enqueue(1)
        fixture.keys.failing = true

        assertEquals(UploadResult.Blocked(DomainError.DeviceKeyUnavailable), fixture.upload())
        assertEquals(1, fixture.outbox.pending.size)
        assertTrue(fixture.gateway.registrations.isEmpty())
    }

    @Test
    fun `changing the server URL triggers a new registration`() = runTest {
        val fixture = TrackerFixture()
        fixture.state.registration = TrackerRegistration("https://old-server:8787", 100, T0)
        fixture.enqueue(1)

        fixture.upload()

        assertEquals(1, fixture.gateway.registrations.size)
        assertEquals(SERVER, fixture.state.registration?.serverUrl)
    }
}

class PairingTest {
    private val serverKey = FakeServerKeys.SERVER_KEY
    private val lan = "https://192.168.1.50:8787"
    private val vpn = "https://100.101.102.103:8787"
    private val invite = PairingInvite("Casa", listOf(lan, vpn), serverKey, "ticket-1", "AAAAAAAAAAAAAAAAAAAAAA", T0.plusSeconds(600))

    private fun unpairedFixture() = TrackerFixture(TrackerConfig(deviceName = "Pixel de Ana"))

    @Test
    fun `pairing uses the first address where the server proves its key, pins it, and registers with proof`() = runTest {
        val fixture = unpairedFixture()
        fixture.gateway.serverKey = serverKey
        fixture.gateway.unreachable += lan

        val result = fixture.pair(invite).successValue()

        assertEquals(PairingResult("Casa", vpn, DeviceApproval.APPROVED), result)
        val config = fixture.config.get()
        assertEquals(vpn, config.serverUrl)
        assertEquals(serverKey, config.serverKey)
        assertEquals(listOf(lan, vpn), config.serverAddresses)
        val claim = assertNotNull(fixture.gateway.registrations.single().pairing)
        assertEquals("ticket-1", claim.ticketId)
        assertTrue(isValidPairingProof(invite.secret, DEVICE_A, fakePublicKey(DEVICE_A), claim.proof))
        assertEquals(vpn, fixture.state.registration?.serverUrl)
    }

    @Test
    fun `a server that cannot prove the invite's key is never paired with`() = runTest {
        val fixture = unpairedFixture()
        fixture.gateway.serverKey = fakePublicKey(DEVICE_B)

        assertEquals(DomainError.ServerIdentityMismatch, fixture.pair(invite).failureError())
        assertEquals("", fixture.config.get().serverUrl)
        assertTrue(fixture.gateway.registrations.isEmpty())
    }

    @Test
    fun `a failed pairing leaves the current one as it was`() = runTest {
        val paired = TrackerConfig(serverUrl = "https://10.0.0.9:8787", deviceName = "Ana", serverKey = "b2xk", serverAddresses = listOf("https://10.0.0.9:8787"))
        val fixture = TrackerFixture(paired)
        fixture.gateway.serverKey = serverKey
        fixture.gateway.registerResponses += Outcome.Failure(DomainError.PairingInvalid)

        assertEquals(DomainError.PairingInvalid, fixture.pair(invite).failureError())

        assertEquals(paired, fixture.config.get())
        assertEquals(null, fixture.state.registration)
        assertEquals(CurrentServer("https://10.0.0.9:8787", keyFingerprint("b2xk")), GetCurrentServer(fixture.config)())
    }

    @Test
    fun `a code that is clearly expired is refused without touching the network`() = runTest {
        val fixture = unpairedFixture()
        fixture.clock.advanceBy(Duration.ofMinutes(16))

        assertEquals(DomainError.PairingInvalid, fixture.pair(invite).failureError())
        assertTrue(fixture.gateway.healthCalls.isEmpty())
    }

    @Test
    fun `uploads ask the paired server to sign its answers with the pinned key`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan)))
        fixture.gateway.serverKey = serverKey
        fixture.enqueue(1)

        fixture.upload()

        assertEquals(listOf<ServerPin>(ServerPin.Key(serverKey)), fixture.gateway.uploadPins)
    }

    @Test
    fun `a server that took over the address fails the handshake and gets no registration and no locations`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan, vpn)))
        fixture.gateway.serverKey = fakePublicKey(DEVICE_B)
        fixture.enqueue(1)

        assertEquals(UploadResult.RetryLater(DomainError.ServerIdentityMismatch), fixture.upload())
        assertTrue(fixture.gateway.registrations.isEmpty())
        assertTrue(fixture.gateway.uploads.isEmpty())
    }

    @Test
    fun `when the address stops answering, the tracker moves to another listed one where its server proves its key`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan, vpn)))
        fixture.gateway.serverKey = serverKey
        fixture.gateway.unreachable += lan

        assertTrue(fixture.failOver())
        assertEquals(vpn, fixture.config.get().serverUrl)

        fixture.gateway.unreachable += vpn
        assertFalse(fixture.failOver())
        assertFalse(TrackerFixture().failOver(), "a server typed by hand has no other addresses")
    }

    @Test
    fun `when no listed address answers, the tracker finds its server on the local network and remembers it`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan, vpn)))
        fixture.gateway.serverKey = serverKey
        fixture.gateway.unreachable += listOf(lan, vpn)
        val other = "https://192.168.1.99:8787"
        val impostor = "https://192.168.1.66:8787"
        val moved = "https://192.168.1.77:8787"
        // Another household's server announces another key: not even asked.
        fixture.discovery.found += DiscoveredServer(other, keyHint = "0000000000000000")
        // Announces our key's hint but cannot prove the key.
        fixture.discovery.found += DiscoveredServer(impostor, serverKeyHint(serverKey))
        fixture.gateway.keysByUrl[impostor] = fakePublicKey(DEVICE_B)
        fixture.discovery.found += DiscoveredServer(moved, serverKeyHint(serverKey))

        assertTrue(fixture.failOver())

        assertEquals(moved, fixture.config.get().serverUrl)
        assertEquals(listOf(moved, lan, vpn), fixture.config.get().serverAddresses)
        assertTrue(fixture.gateway.healthCalls.none { it.first == other })
    }

    @Test
    fun `a failover never overwrites a server saved while it was looking`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan)))
        fixture.gateway.unreachable += lan
        val moved = "https://192.168.1.77:8787"
        fixture.discovery.found += DiscoveredServer(moved, serverKeyHint(serverKey))
        // The user saves another server while discovery listens.
        fixture.discovery.onDiscover = {
            fixture.config.update { it.copy(serverUrl = "https://10.0.0.5:8787", serverKey = "", serverFingerprint = "0000-1111-2222-3333") }
        }

        assertFalse(fixture.failOver())

        assertEquals("https://10.0.0.5:8787", fixture.config.get().serverUrl)
    }

    @Test
    fun `a server that is simply off costs one network scan every five minutes, not one per upload`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan)))
        fixture.gateway.unreachable += lan

        repeat(3) { assertFalse(fixture.failOver()) }
        assertEquals(1, fixture.discovery.calls)

        fixture.clock.advanceBy(FailOverServerAddress.DISCOVERY_INTERVAL)
        assertFalse(fixture.failOver())
        assertEquals(2, fixture.discovery.calls)
    }

    @Test
    fun `the fingerprint says which server it is, not the address`() = runTest {
        val repository = InMemoryTrackerConfigRepository(
            TrackerConfig(serverUrl = lan, deviceName = "Ana", serverKey = serverKey, serverAddresses = listOf(lan, vpn)),
        )
        val update = UpdateTrackerConfig(repository)
        val form = repository.get().copy(serverFingerprint = assertNotNull(repository.get().pinnedFingerprint))

        // The same server at an address typed by hand: still trusted by its whole key.
        update(form.copy(serverUrl = "https://192.168.1.99:8787")).successValue()
        assertEquals(serverKey, repository.get().serverKey)
        assertEquals(listOf(lan, vpn), repository.get().serverAddresses)

        // Another fingerprint is another server, trusted by that fingerprint until the first contact.
        update(form.copy(serverFingerprint = "0000-1111-2222-3333")).successValue()
        assertEquals("", repository.get().serverKey)
        assertEquals(emptyList(), repository.get().serverAddresses)
        assertEquals(ServerPin.Fingerprint("0000-1111-2222-3333"), repository.get().serverPin)
    }

    @Test
    fun `a server typed in by hand is trusted by its fingerprint until registering gives its whole key`() = runTest {
        val fingerprint = assertNotNull(keyFingerprint(serverKey))
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverFingerprint = fingerprint))
        fixture.enqueue(1)

        assertEquals(UploadResult.Completed(sent = 1, rejected = 0, hasMore = false), fixture.upload())

        val config = fixture.config.get()
        assertEquals(serverKey, config.serverKey)
        assertEquals("", config.serverFingerprint)
        // Health by the fingerprint; registration and the batch by the key the server just proved.
        assertEquals(listOf(ServerPin.Fingerprint(fingerprint), ServerPin.Key(serverKey), ServerPin.Key(serverKey)), fixture.gateway.pins)
        assertEquals(listOf<ServerPin>(ServerPin.Key(serverKey)), fixture.gateway.uploadPins)
    }

    @Test
    fun `a server whose key does not match the typed fingerprint is told nothing`() = runTest {
        val fingerprint = assertNotNull(keyFingerprint(fakePublicKey(DEVICE_B)))
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana", serverFingerprint = fingerprint))
        fixture.enqueue(1)

        assertEquals(UploadResult.RetryLater(DomainError.ServerIdentityMismatch), fixture.upload())

        assertTrue(fixture.gateway.registrations.isEmpty())
        assertTrue(fixture.gateway.uploads.isEmpty())
        assertEquals("", fixture.config.get().serverKey)
    }

    @Test
    fun `with nothing to trust the server by, nothing is sent`() = runTest {
        val fixture = TrackerFixture(TrackerConfig(serverUrl = lan, deviceName = "Ana"))
        fixture.enqueue(1)

        assertEquals(UploadResult.Blocked(DomainError.ServerNotTrusted), fixture.upload())
        assertEquals(DomainError.ServerNotTrusted, fixture.register().failureError())
        assertTrue(fixture.gateway.pins.isEmpty())
    }
}

class TrackerSettingsTest {
    @Test
    fun `updating the configuration normalizes it`() = runTest {
        val repository = InMemoryTrackerConfigRepository()
        val update = UpdateTrackerConfig(repository)

        val saved = update(
            TrackerConfig(serverUrl = " 192.168.1.10:8787/ ", deviceName = " Ana ", serverFingerprint = " 3f9a 01bc-77d2:e410 "),
        ).successValue()

        assertEquals("https://192.168.1.10:8787", saved.serverUrl)
        assertEquals("Ana", saved.deviceName)
        assertEquals("3F9A-01BC-77D2-E410", saved.serverFingerprint)
        assertEquals(saved, repository.get())
    }

    @Test
    fun `an invalid configuration reports every violation and is not saved`() = runTest {
        val repository = InMemoryTrackerConfigRepository()

        val error = assertIs<DomainError.Validation>(UpdateTrackerConfig(repository)(TrackerConfig(intervalSeconds = 5)).failureError())

        assertEquals(listOf("serverUrl", "serverFingerprint", "deviceName", "intervalSeconds"), error.violations.map { it.field })
        assertEquals(TrackerConfig(), repository.get())
    }

    @Test
    fun `testing a connection reports latency, compatibility and clock offset`() = runTest {
        val clock = FakeClock()
        val gateway = FakeServerGateway(clock)
        gateway.healthResponses += Outcome.Success(ServerInfo("Casa", "0.1.0", 1, T0.plusSeconds(90)))

        val check = TestServerConnection(gateway, clock, TestTimeSource())(SERVER, SERVER_FINGERPRINT).successValue()

        assertTrue(check.compatible)
        assertEquals(Duration.ZERO, check.latency)
        assertEquals(Duration.ofSeconds(90), check.clockOffset)
        assertEquals("Casa", check.server.name)
    }

    @Test
    fun `the key fingerprint is four groups of hex digits that change with the key`() = runTest {
        val keys = FakeDeviceKeys()
        val fingerprint = GetDeviceKeyFingerprint(GetOrCreateDeviceIdentity(InMemoryIdentityRepository(DEVICE_A), SequentialIdGenerator()), keys)

        val first = fingerprint()
        keys.generation = 2

        assertTrue(Regex("^[0-9A-F]{4}(-[0-9A-F]{4}){3}$").matches(assertNotNull(first)), first)
        assertTrue(first != fingerprint())
        keys.failing = true
        assertNull(fingerprint())
    }

    @Test
    fun `testing a connection trusts only the server with the typed fingerprint`() = runTest {
        val gateway = FakeServerGateway()
        val test = TestServerConnection(gateway, FakeClock())

        test(SERVER, SERVER_FINGERPRINT.lowercase().replace("-", " ")).successValue()
        assertEquals(listOf<ServerPin>(ServerPin.Fingerprint(SERVER_FINGERPRINT)), gateway.pins)

        assertEquals(DomainError.ServerIdentityMismatch, test(SERVER, "0000-1111-2222-3333").failureError())
    }

    @Test
    fun `testing a malformed URL or fingerprint fails without a request`() = runTest {
        val gateway = FakeServerGateway()
        val test = TestServerConnection(gateway, FakeClock())

        val error = assertIs<DomainError.Validation>(test("not a url", "3F9A").failureError())
        assertEquals(listOf("serverUrl", "serverFingerprint"), error.violations.map { it.field })
        val plain = assertIs<DomainError.Validation>(test("http://192.168.1.10:8787", SERVER_FINGERPRINT).failureError())
        assertEquals(FieldViolation.HTTPS_REQUIRED, plain.violations.single().rule)
        assertTrue(gateway.pins.isEmpty())
    }
}
