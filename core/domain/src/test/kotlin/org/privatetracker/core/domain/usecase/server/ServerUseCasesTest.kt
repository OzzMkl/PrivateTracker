package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.DeviceStatus
import org.privatetracker.core.domain.model.PairingClaim
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.RejectionReason
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SessionEndReason
import org.privatetracker.core.domain.model.pairingProof
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FakeServerKeys
import org.privatetracker.core.domain.testing.FakeSignatureVerifier
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
import org.privatetracker.core.domain.testing.InMemoryNonces
import org.privatetracker.core.domain.testing.InMemoryPairingTickets
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aDevice
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.aSignedRequest
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.fakePublicKey
import org.privatetracker.core.domain.testing.locationId
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wires the server use cases over in-memory storage, like the DI graph does over Room. */
private class ServerFixture(config: ServerConfig = ServerConfig()) {
    val clock = FakeClock()
    val store = InMemoryServerStore()
    val config = InMemoryServerConfigRepository(config)
    val sessionTracker = SessionTracker(store, SequentialIdGenerator())
    val verifySignature = VerifyRequestSignature(FakeSignatureVerifier, InMemoryNonces(), clock)
    val tickets = InMemoryPairingTickets()
    val registerDevice = RegisterOrUpdateDevice(
        store, this.config, sessionTracker, FakeSignatureVerifier, verifySignature, tickets, ImmediateTransactionRunner, clock,
    )
    val createInvite = CreatePairingInvite(tickets, FakeServerKeys(), this.config, clock)
    val observePaired = ObservePairedDevice(tickets, store)
    val authenticate = AuthenticateDevice(store, verifySignature)
    val setApproval = SetDeviceApproval(store, ImmediateTransactionRunner)
    val ingest = IngestLocationBatch(
        store, store, store, this.config, sessionTracker, LocationValidator(clock), ImmediateTransactionRunner, clock,
    )
    val overviews = GetDeviceOverviews(store, this.config, clock)
    val detail = GetDeviceDetail(store, store, this.config, clock)
    val observeDetail = ObserveDeviceDetail(store, store, this.config, clock)
    val closeInactive = CloseInactiveSessions(store, sessionTracker, this.config, ImmediateTransactionRunner, clock)
    val closeAll = CloseAllSessions(store, sessionTracker, ImmediateTransactionRunner, clock)
    val purge = PurgeExpiredLocations(store, this.config, clock)
    val remove = RemoveDevice(store)
    private var nonces = 0

    /** A request of [deviceId] signed now with [publicKey], each with a fresh nonce. */
    fun signed(deviceId: DeviceId = DEVICE_A, publicKey: String = fakePublicKey(deviceId)) =
        aSignedRequest(deviceId, publicKey, clock.now(), nonce = "nonce-${++nonces}")

    suspend fun register(registration: DeviceRegistration, remoteAddress: String? = null) =
        registerDevice(registration, signed(registration.deviceId, registration.publicKey), remoteAddress)

    /** Registered and approved, like a tracker the owner already let in; it registers again, which opens a session. */
    suspend fun enroll(registration: DeviceRegistration = aRegistration(), remoteAddress: String? = null) {
        register(registration, remoteAddress).successValue()
        setApproval(registration.deviceId, DeviceApproval.APPROVED).successValue()
        register(registration, remoteAddress).successValue()
    }
}

class SessionTrackerTest {
    private val fixture = ServerFixture()
    private val limit = Duration.ofMinutes(5)

    @Test
    fun `activity within the limit renews the open session`() = runTest {
        val first = fixture.sessionTracker.recordActivity(DEVICE_A, T0, limit, "10.0.0.2", "0.1.0")
        val renewed = fixture.sessionTracker.recordActivity(DEVICE_A, T0.plusSeconds(300), limit, null, null)

        assertEquals(first.id, renewed.id)
        assertEquals(T0.plusSeconds(300), renewed.lastActivityAt)
        assertEquals("10.0.0.2", renewed.remoteAddress)
    }

    @Test
    fun `activity after the limit closes the old session when it went silent and opens a new one`() = runTest {
        val first = fixture.sessionTracker.recordActivity(DEVICE_A, T0, limit, null, null)
        val second = fixture.sessionTracker.recordActivity(DEVICE_A, T0.plusSeconds(301), limit, null, null)

        val closed = fixture.store.storedSessions.first { it.id == first.id }
        assertEquals(T0, closed.endedAt)
        assertEquals(SessionEndReason.INACTIVITY, closed.endReason)
        assertTrue(second.isOpen)
        assertEquals(second, fixture.store.findOpen(DEVICE_A))
    }
}

class RegisterOrUpdateDeviceTest {
    @Test
    fun `a new device is stored with its key, pending approval and without a session`() = runTest {
        val fixture = ServerFixture()

        val result = fixture.register(aRegistration(name = "  Pixel de Ana "), "10.0.0.2").successValue()

        assertTrue(result.created)
        assertEquals(100, result.maxBatchSize)
        assertEquals(DeviceApproval.PENDING, result.approval)
        val device = fixture.store.get(DEVICE_A)!!
        assertEquals("Pixel de Ana", device.name)
        assertEquals(T0, device.createdAt)
        assertEquals(fakePublicKey(DEVICE_A), device.publicKey)
        assertEquals(DeviceApproval.PENDING, device.approval)
        assertNull(fixture.store.findOpen(DEVICE_A))
    }

    @Test
    fun `an approved device registering again refreshes its details and opens a session`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration())
        fixture.setApproval(DEVICE_A, DeviceApproval.APPROVED).successValue()
        fixture.clock.advanceBy(Duration.ofMinutes(1))

        val result = fixture.register(aRegistration(name = "Ana"), "10.0.0.2").successValue()

        assertFalse(result.created)
        assertEquals(DeviceApproval.APPROVED, result.approval)
        val device = fixture.store.get(DEVICE_A)!!
        assertEquals("Ana", device.name)
        assertEquals(T0, device.createdAt)
        assertEquals(T0.plusSeconds(60), device.lastSeenAt)
        assertEquals("10.0.0.2", fixture.store.findOpen(DEVICE_A)?.remoteAddress)
    }

    @Test
    fun `a closed server rejects new devices but still serves known ones`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll(aRegistration(id = DEVICE_A))
        fixture.config.update { it.copy(acceptNewDevices = false) }

        assertEquals(DomainError.NewDevicesDisabled, fixture.register(aRegistration(id = DEVICE_B)).failureError())
        fixture.register(aRegistration(id = DEVICE_A)).successValue()
        assertNull(fixture.store.get(DEVICE_B))
    }

    @Test
    fun `invalid registrations are refused before touching storage`() = runTest {
        val fixture = ServerFixture()

        assertIs<DomainError.UnsupportedProtocolVersion>(fixture.register(aRegistration(protocolVersion = 2)).failureError())
        assertIs<DomainError.Validation>(fixture.register(aRegistration(name = " ")).failureError())
        assertEquals(
            DomainError.Validation("public_key", FieldViolation.INVALID_FORMAT),
            fixture.register(aRegistration(publicKey = "not a key")).failureError(),
        )
        assertNull(fixture.store.get(DEVICE_A))
    }

    @Test
    fun `the request must come from the registered device and be signed with the registered key`() = runTest {
        val fixture = ServerFixture()
        val invalid = DomainError.AuthenticationFailed(AuthFailure.INVALID)

        val otherKey = fixture.signed(DEVICE_A, publicKey = fakePublicKey(DEVICE_A, generation = 2))
        assertEquals(invalid, fixture.registerDevice(aRegistration(), otherKey, null).failureError())
        val otherDevice = fixture.signed(DEVICE_B, publicKey = fakePublicKey(DEVICE_A))
        assertEquals(invalid, fixture.registerDevice(aRegistration(), otherDevice, null).failureError())
        assertNull(fixture.store.get(DEVICE_A))
    }

    @Test
    fun `a known device cannot switch to another key`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()

        val error = fixture.register(aRegistration(publicKey = fakePublicKey(DEVICE_A, generation = 2))).failureError()

        assertEquals(DomainError.AuthenticationFailed(AuthFailure.KEY_MISMATCH), error)
        assertEquals(fakePublicKey(DEVICE_A), fixture.store.get(DEVICE_A)?.publicKey)
    }

    @Test
    fun `a rejected device stays rejected when it registers again`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration())
        fixture.setApproval(DEVICE_A, DeviceApproval.REJECTED).successValue()

        assertEquals(DomainError.DeviceRejected, fixture.register(aRegistration()).failureError())
        assertEquals(DeviceApproval.REJECTED, fixture.store.get(DEVICE_A)?.approval)
    }

    @Test
    fun `a device that 0_1 registered gets its key on the next registration and waits for approval`() = runTest {
        val fixture = ServerFixture()
        fixture.store.insert(aDevice().copy(publicKey = null, approval = DeviceApproval.APPROVED))

        val result = fixture.register(aRegistration()).successValue()

        assertEquals(DeviceApproval.PENDING, result.approval)
        assertEquals(fakePublicKey(DEVICE_A), fixture.store.get(DEVICE_A)?.publicKey)
    }

    @Test
    fun `a device from 0_1 cannot get a key while the server refuses new devices`() = runTest {
        val fixture = ServerFixture()
        fixture.store.insert(aDevice().copy(publicKey = null))
        fixture.config.update { it.copy(acceptNewDevices = false) }

        assertEquals(DomainError.NewDevicesDisabled, fixture.register(aRegistration()).failureError())
        assertNull(fixture.store.get(DEVICE_A)?.publicKey)
    }

    @Test
    fun `new requests are refused once too many wait for a decision, known devices still get in`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()
        repeat(RegisterOrUpdateDevice.MAX_PENDING_DEVICES) {
            fixture.register(aRegistration(id = DeviceId.of(UUID.randomUUID().toString()))).successValue()
        }

        val oneMore = aRegistration(id = DeviceId.of(UUID.randomUUID().toString()))
        assertEquals(DomainError.NewDevicesDisabled, fixture.register(oneMore).failureError())
        fixture.register(aRegistration()).successValue()
    }

    @Test
    fun `approving an unknown device fails`() = runTest {
        assertEquals(DomainError.DeviceNotFound, ServerFixture().setApproval(DEVICE_A, DeviceApproval.APPROVED).failureError())
    }
}

class PairingTest {
    private fun claim(invite: PairingInvite, device: DeviceId = DEVICE_A, publicKey: String = fakePublicKey(device)) =
        PairingClaim(invite.ticketId, pairingProof(invite.secret, device, publicKey))

    @Test
    fun `an invite carries the server's name, addresses and key, and a ticket that expires`() = runTest {
        val fixture = ServerFixture()

        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()

        assertEquals("PrivateTracker Server", invite.serverName)
        assertEquals(FakeServerKeys.SERVER_KEY, invite.serverKey)
        assertEquals(T0.plus(CreatePairingInvite.VALIDITY), invite.expiresAt)
        assertEquals(invite.secret, fixture.tickets.get(invite.ticketId)?.secret)
        assertIs<DomainError.Validation>(fixture.createInvite(emptyList()).failureError())
    }

    @Test
    fun `a valid claim approves a new device at once, even while new devices are refused`() = runTest {
        val fixture = ServerFixture()
        fixture.config.update { it.copy(acceptNewDevices = false) }
        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()

        val result = fixture.register(aRegistration().copy(pairing = claim(invite)), "10.0.0.2").successValue()

        assertEquals(DeviceApproval.APPROVED, result.approval)
        assertEquals(DeviceApproval.APPROVED, fixture.store.get(DEVICE_A)?.approval)
        assertEquals("10.0.0.2", fixture.store.findOpen(DEVICE_A)?.remoteAddress)
        assertEquals(DEVICE_A, fixture.observePaired(invite.ticketId).first()?.id)
    }

    @Test
    fun `a ticket serves one phone, which may repeat its registration`() = runTest {
        val fixture = ServerFixture()
        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()
        fixture.register(aRegistration().copy(pairing = claim(invite))).successValue()

        fixture.register(aRegistration().copy(pairing = claim(invite))).successValue()
        val other = aRegistration(id = DEVICE_B).copy(pairing = claim(invite, DEVICE_B))
        assertEquals(DomainError.PairingInvalid, fixture.register(other).failureError())
        assertNull(fixture.store.get(DEVICE_B))
    }

    @Test
    fun `expired, withdrawn, unknown or forged claims are refused and leave nothing behind`() = runTest {
        val fixture = ServerFixture()
        val old = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()
        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()

        assertEquals(DomainError.PairingInvalid, fixture.register(aRegistration().copy(pairing = claim(old))).failureError())
        val unknown = PairingClaim("nope", claim(invite).proof)
        assertEquals(DomainError.PairingInvalid, fixture.register(aRegistration().copy(pairing = unknown)).failureError())
        // A proof made for another key cannot vouch for this one.
        val forged = claim(invite, publicKey = fakePublicKey(DEVICE_A, generation = 2))
        assertEquals(DomainError.PairingInvalid, fixture.register(aRegistration().copy(pairing = forged)).failureError())
        fixture.clock.advanceBy(CreatePairingInvite.VALIDITY)
        assertEquals(DomainError.PairingInvalid, fixture.register(aRegistration().copy(pairing = claim(invite))).failureError())

        assertNull(fixture.store.get(DEVICE_A))
        assertNull(fixture.tickets.get(invite.ticketId)?.usedBy)
    }

    @Test
    fun `a ticket never gives a known id another key, but lets the same phone back in`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration())
        fixture.setApproval(DEVICE_A, DeviceApproval.REJECTED).successValue()
        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()

        // Device ids travel in the clear: someone with a ticket must not take over this one.
        val newKey = fakePublicKey(DEVICE_A, generation = 2)
        val takeover = aRegistration(publicKey = newKey).copy(pairing = claim(invite, publicKey = newKey))
        assertEquals(DomainError.AuthenticationFailed(AuthFailure.KEY_MISMATCH), fixture.register(takeover).failureError())
        assertNull(fixture.tickets.get(invite.ticketId)?.usedBy)

        // The owner showed a new code to the same phone: that is consent, even after a rejection.
        assertEquals(DeviceApproval.APPROVED, fixture.register(aRegistration().copy(pairing = claim(invite))).successValue().approval)
        assertEquals(fakePublicKey(DEVICE_A), fixture.store.get(DEVICE_A)?.publicKey)
    }

    @Test
    fun `a used ticket only serves its phone's retries, and not once the owner rejected or removed it`() = runTest {
        val fixture = ServerFixture()
        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()
        val paired = aRegistration().copy(pairing = claim(invite))
        fixture.register(paired).successValue()

        fixture.setApproval(DEVICE_A, DeviceApproval.REJECTED).successValue()
        assertEquals(DomainError.PairingInvalid, fixture.register(paired).failureError())
        assertEquals(DeviceApproval.REJECTED, fixture.store.get(DEVICE_A)?.approval)

        fixture.remove(DEVICE_A).successValue()
        assertEquals(DomainError.PairingInvalid, fixture.register(paired).failureError())
        assertNull(fixture.store.get(DEVICE_A))
    }

    @Test
    fun `a proof is worth nothing for another device, and a withdrawn ticket for anyone`() = runTest {
        val fixture = ServerFixture()
        val invite = fixture.createInvite(listOf("https://192.168.1.50:8787")).successValue()

        val borrowed = aRegistration(id = DEVICE_B).copy(pairing = claim(invite, DEVICE_A))
        assertEquals(DomainError.PairingInvalid, fixture.register(borrowed).failureError())

        WithdrawPairingInvite(fixture.tickets)(invite.ticketId)
        assertEquals(DomainError.PairingInvalid, fixture.register(aRegistration().copy(pairing = claim(invite))).failureError())
        assertNull(fixture.store.get(DEVICE_A))
    }
}

class AuthenticateDeviceTest {
    @Test
    fun `an approved device with a fresh, valid signature is authenticated`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()

        assertEquals(DEVICE_A, fixture.authenticate(fixture.signed()).successValue().id)
    }

    @Test
    fun `unknown devices and devices without a key are told to register`() = runTest {
        val fixture = ServerFixture()
        assertEquals(DomainError.DeviceNotRegistered, fixture.authenticate(fixture.signed()).failureError())

        fixture.store.insert(aDevice().copy(publicKey = null))
        assertEquals(DomainError.DeviceNotRegistered, fixture.authenticate(fixture.signed()).failureError())
    }

    @Test
    fun `a signature by another key, too old, too far ahead or replayed is refused`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()
        fun failure(reason: AuthFailure) = DomainError.AuthenticationFailed(reason)

        val forged = fixture.signed(publicKey = fakePublicKey(DEVICE_A, generation = 2))
        assertEquals(failure(AuthFailure.INVALID), fixture.authenticate(forged).failureError())
        val old = aSignedRequest(signedAt = T0.minus(Duration.ofMinutes(6)), nonce = "old")
        assertEquals(failure(AuthFailure.EXPIRED), fixture.authenticate(old).failureError())
        val ahead = aSignedRequest(signedAt = T0.plus(Duration.ofMinutes(6)), nonce = "ahead")
        assertEquals(failure(AuthFailure.EXPIRED), fixture.authenticate(ahead).failureError())

        val once = fixture.signed()
        fixture.authenticate(once).successValue()
        assertEquals(failure(AuthFailure.REPLAYED), fixture.authenticate(once).failureError())
    }

    @Test
    fun `a forged request does not use up the nonce of the real one`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()

        val forged = aSignedRequest(publicKey = fakePublicKey(DEVICE_A, generation = 2), nonce = "shared")
        fixture.authenticate(forged).failureError()

        fixture.authenticate(aSignedRequest(nonce = "shared")).successValue()
    }

    @Test
    fun `pending and rejected devices are refused, but only after their signature verifies`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration())

        assertEquals(DomainError.DevicePendingApproval, fixture.authenticate(fixture.signed()).failureError())
        val forged = fixture.signed(publicKey = fakePublicKey(DEVICE_A, generation = 2))
        assertEquals(DomainError.AuthenticationFailed(AuthFailure.INVALID), fixture.authenticate(forged).failureError())

        fixture.setApproval(DEVICE_A, DeviceApproval.REJECTED).successValue()
        assertEquals(DomainError.DeviceRejected, fixture.authenticate(fixture.signed()).failureError())
    }
}

class IngestLocationBatchTest {
    private suspend fun registeredFixture() = ServerFixture().also { it.enroll() }

    @Test
    fun `locations from a device nobody approved are refused`() = runTest {
        val fixture = ServerFixture()
        fixture.register(aRegistration())

        assertEquals(DomainError.DevicePendingApproval, fixture.ingest(DEVICE_A, listOf(aLocation()), null).failureError())
        assertTrue(fixture.store.storedLocations.isEmpty())
    }

    @Test
    fun `locations from an unknown device are refused`() = runTest {
        val fixture = ServerFixture()

        assertEquals(DomainError.DeviceNotRegistered, fixture.ingest(DEVICE_A, listOf(aLocation()), null).failureError())
        assertTrue(fixture.store.storedLocations.isEmpty())
    }

    @Test
    fun `accepted locations are stored with arrival time and become the last location`() = runTest {
        val fixture = registeredFixture()
        fixture.clock.advanceBy(Duration.ofSeconds(30))

        val result = fixture.ingest(
            DEVICE_A,
            listOf(aLocation(1, recordedAt = T0), aLocation(2, recordedAt = T0.plusSeconds(20))),
            "10.0.0.2",
        ).successValue()

        assertEquals(listOf(locationId(1), locationId(2)), result.accepted)
        assertTrue(fixture.store.storedLocations.all { it.location.receivedAt == T0.plusSeconds(30) })
        val row = fixture.store.getWithLastLocation(DEVICE_A)!!
        assertEquals(locationId(2), row.lastLocation?.id)
        assertEquals(T0.plusSeconds(30), row.device.lastSeenAt)
        assertEquals(2, fixture.store.findOpen(DEVICE_A)?.locationsReceived)
    }

    @Test
    fun `resending a batch reports duplicates and stores nothing twice`() = runTest {
        val fixture = registeredFixture()
        val batch = listOf(aLocation(1), aLocation(2))
        fixture.ingest(DEVICE_A, batch, null)

        val again = fixture.ingest(DEVICE_A, batch, null).successValue()

        assertEquals(emptyList(), again.accepted)
        assertEquals(listOf(locationId(1), locationId(2)), again.duplicates)
        assertEquals(2, fixture.store.storedLocations.size)
        assertEquals(2, fixture.store.findOpen(DEVICE_A)?.locationsReceived)
    }

    @Test
    fun `invalid locations are rejected without blocking the rest of the batch`() = runTest {
        val fixture = registeredFixture()

        val result = fixture.ingest(DEVICE_A, listOf(aLocation(1, latitude = 95.0), aLocation(2)), null).successValue()

        assertEquals(listOf(locationId(2)), result.accepted)
        assertEquals(RejectionReason.INVALID_COORDINATES, result.rejected.single().reason)
        assertEquals(locationId(1).value, result.rejected.single().id)
    }

    @Test
    fun `an older batch arriving late does not move the last location back`() = runTest {
        val fixture = registeredFixture()
        fixture.ingest(DEVICE_A, listOf(aLocation(2, recordedAt = T0.plusSeconds(60))), null)

        fixture.ingest(DEVICE_A, listOf(aLocation(1, recordedAt = T0)), null).successValue()

        assertEquals(locationId(2), fixture.store.getWithLastLocation(DEVICE_A)?.lastLocation?.id)
    }

    @Test
    fun `the device id comes from the request path, never from the payload`() = runTest {
        val fixture = registeredFixture()

        fixture.ingest(DEVICE_A, listOf(aLocation(1, deviceId = DEVICE_B)), null).successValue()

        assertEquals(DEVICE_A, fixture.store.storedLocations.single().location.deviceId)
    }

    @Test
    fun `batches over the configured maximum are refused`() = runTest {
        val fixture = registeredFixture()
        fixture.config.update { it.copy(maxBatchSize = 2) }

        val error = fixture.ingest(DEVICE_A, (1..3).map { aLocation(it) }, null).failureError()

        assertEquals(DomainError.BatchTooLarge(2), error)
    }
}

class DeviceQueriesTest {
    @Test
    fun `overviews list the most recently seen devices first with their status`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll(aRegistration(id = DEVICE_A))
        fixture.clock.advanceBy(Duration.ofMinutes(10))
        fixture.enroll(aRegistration(id = DEVICE_B, name = "Moto"))

        val overviews = fixture.overviews()

        assertEquals(listOf(DEVICE_B, DEVICE_A), overviews.map { it.device.id })
        assertEquals(
            listOf(DeviceStatus.ONLINE, DeviceStatus.STALE),
            overviews.map { it.status },
        )
    }

    @Test
    fun `detail hides a session whose device went silent`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()
        assertTrue(fixture.detail(DEVICE_A).successValue().currentSession != null)

        fixture.clock.advanceBy(Duration.ofMinutes(6))

        assertNull(fixture.detail(DEVICE_A).successValue().currentSession)
        assertEquals(DomainError.DeviceNotFound, fixture.detail(DEVICE_B).failureError())
    }

    @Test
    fun `observed detail carries recent sessions and ends as null when the device is removed`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()
        fixture.clock.advanceBy(Duration.ofMinutes(10))
        fixture.register(aRegistration())

        val detail = assertNotNull(fixture.observeDetail(DEVICE_A).first())
        assertEquals(2, detail.recentSessions.size)
        assertEquals(detail.recentSessions.first(), detail.currentSession)

        fixture.remove(DEVICE_A).successValue()
        assertNull(fixture.observeDetail(DEVICE_A).first())
    }
}

class MaintenanceTest {
    @Test
    fun `purge deletes expired locations but keeps each device's last one`() = runTest {
        val fixture = ServerFixture(ServerConfig(retentionDays = 1))
        fixture.enroll()
        fixture.ingest(DEVICE_A, listOf(aLocation(1), aLocation(2, recordedAt = T0.plusSeconds(1))), null)
        fixture.clock.advanceBy(Duration.ofDays(2))

        assertEquals(1, fixture.purge())

        assertEquals(listOf(locationId(2)), fixture.store.storedLocations.map { it.location.id })
    }

    @Test
    fun `only sessions silent beyond the threshold are closed`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll(aRegistration(id = DEVICE_A))
        fixture.clock.advanceBy(Duration.ofMinutes(4))
        fixture.enroll(aRegistration(id = DEVICE_B))
        fixture.clock.advanceBy(Duration.ofMinutes(2))

        assertEquals(1, fixture.closeInactive())

        assertNull(fixture.store.findOpen(DEVICE_A))
        assertTrue(fixture.store.findOpen(DEVICE_B) != null)
    }

    @Test
    fun `stopping the server closes every open session`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll(aRegistration(id = DEVICE_A))
        fixture.enroll(aRegistration(id = DEVICE_B))

        assertEquals(2, fixture.closeAll())

        assertTrue(fixture.store.storedSessions.all { it.endReason == SessionEndReason.SERVER_STOPPED })
    }

    @Test
    fun `removing a device removes its locations and sessions`() = runTest {
        val fixture = ServerFixture()
        fixture.enroll()
        fixture.ingest(DEVICE_A, listOf(aLocation()), null)

        fixture.remove(DEVICE_A).successValue()

        assertTrue(fixture.store.storedLocations.isEmpty())
        assertTrue(fixture.store.storedSessions.isEmpty())
        assertEquals(DomainError.DeviceNotFound, fixture.remove(DEVICE_A).failureError())
    }
}
