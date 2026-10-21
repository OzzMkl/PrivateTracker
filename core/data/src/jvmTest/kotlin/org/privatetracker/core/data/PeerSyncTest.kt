package org.privatetracker.core.data

import androidx.room.useWriterConnection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.data.repository.RoomPeerRepository
import org.privatetracker.core.data.repository.RoomSessionRepository
import org.privatetracker.core.data.repository.RoomSyncRepository
import org.privatetracker.core.data.repository.RoomTransactionRunner
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.inMemory
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.DeviceRegistration
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.PeerJoin
import org.privatetracker.core.domain.model.PeerWelcome
import org.privatetracker.core.domain.model.RemovedDevice
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.SignedPeerRequest
import org.privatetracker.core.domain.model.SyncBatch
import org.privatetracker.core.domain.model.SyncCursor
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.peerLabel
import org.privatetracker.core.domain.port.PeerGateway
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FakeServerKeys
import org.privatetracker.core.domain.testing.FakeSignatureVerifier
import org.privatetracker.core.domain.testing.InMemoryGeofences
import org.privatetracker.core.domain.testing.InMemoryNonces
import org.privatetracker.core.domain.testing.InMemoryPairingTickets
import org.privatetracker.core.domain.testing.InMemoryPeerTickets
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.RecordingGeofenceAlerts
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.aRegistration
import org.privatetracker.core.domain.testing.aSignedRequest
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.fakePublicKey
import org.privatetracker.core.domain.testing.fakeServerKey
import org.privatetracker.core.domain.testing.fakeSignature
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.domain.usecase.server.AcceptPeer
import org.privatetracker.core.domain.usecase.server.AnswerPeerExchange
import org.privatetracker.core.domain.usecase.server.CreatePeerInvite
import org.privatetracker.core.domain.usecase.server.EvaluateGeofences
import org.privatetracker.core.domain.usecase.server.ExchangeWithPeer
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.JoinPeer
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.usecase.server.RemovePeer
import org.privatetracker.core.domain.usecase.server.RenameDevice
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval
import org.privatetracker.core.domain.usecase.server.SyncBatches
import org.privatetracker.core.domain.usecase.server.SyncWithPeers
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.validation.LocationValidator
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two servers on real Room databases, paired and exchanging as they do over HTTP, with the requests
 * signed and checked; only the transport is a direct call.
 */
class PeerSyncTest {
    private val clock = FakeClock()
    private val network = mutableMapOf<String, Server>()
    private val servers = mutableListOf<Server>()

    @AfterEach
    fun tearDown() = servers.forEach { it.database.close() }

    private inner class Server(
        val name: String,
        locationLimit: Int = SyncBatches.LOCATIONS_PER_BATCH,
        retentionDays: Int = 30,
        deviceLimit: Int = SyncBatches.DEVICES_PER_BATCH,
    ) {
        val url = "https://$name.test"
        val key = fakeServerKey(name)
        val database: ServerDatabase = ServerDatabase.inMemory()
        val config = InMemoryServerConfigRepository(ServerConfig(serverName = name, retentionDays = retentionDays))
        val devices = RoomDeviceRepository(database)
        private val sessions = RoomSessionRepository(database.sessions(), database.devices())
        val locations = RoomLocationRepository(database.locations(), database.devices(), database.sessions())
        val peers = RoomPeerRepository(database)
        private val transactions = RoomTransactionRunner(database)
        private val alerts = RecordingGeofenceAlerts()
        private val sessionTracker = SessionTracker(sessions, SequentialIdGenerator())
        private val verify = VerifyRequestSignature(FakeSignatureVerifier, InMemoryNonces(), clock)
        private val keys = FakeServerKeys(key)
        private val tickets = InMemoryPeerTickets()
        private val addresses: suspend () -> List<String> = { listOf(url) }
        private val batches = SyncBatches(
            devices, RoomSyncRepository(database), peers, config, FakeSignatureVerifier, LocationValidator(clock),
            EvaluateGeofences(InMemoryGeofences(), alerts, clock), clock, locationLimit = locationLimit, deviceLimit = deviceLimit,
        )
        private val register = RegisterOrUpdateDevice(
            devices, config, sessionTracker, FakeSignatureVerifier, verify, InMemoryPairingTickets(), transactions, clock,
        )
        val approve = SetDeviceApproval(devices, transactions, clock)
        val rename = RenameDevice(devices, transactions, clock)
        val remove = RemoveDevice(devices, clock)
        private val ingest = IngestLocationBatch(
            devices, locations, sessions, config, sessionTracker, LocationValidator(clock), transactions, clock,
            EvaluateGeofences(InMemoryGeofences(), alerts, clock), alerts,
        )
        val invite = CreatePeerInvite(tickets, keys, config, clock)
        val accept = AcceptPeer(peers, tickets, keys, config, FakeSignatureVerifier, verify, addresses, clock)
        val answer = AnswerPeerExchange(peers, batches, verify, transactions, alerts, addresses)
        val gateway = Gateway(this)
        val join = JoinPeer(peers, gateway, keys, config, addresses, clock)
        val exchange = ExchangeWithPeer(peers, batches, gateway, transactions, alerts, addresses, clock)
        val syncAll = SyncWithPeers(peers, exchange, clock)
        val unpair = RemovePeer(peers)
        var online = true
        private var nonces = 0

        init {
            network[url] = this
            servers += this
        }

        suspend fun enroll(registration: DeviceRegistration) {
            checkIn(registration)
            approve(registration.deviceId, DeviceApproval.APPROVED).successValue()
        }

        /** The tracker registers again, as it does at start or after an update. */
        suspend fun checkIn(registration: DeviceRegistration) =
            register(registration, aSignedRequest(registration.deviceId, registration.publicKey, clock.now(), "r-${++nonces}"), null).successValue()

        suspend fun send(device: DeviceId, vararg seconds: Long) =
            ingest(device, seconds.map { aLocation(it.toInt(), device, recordedAt = clock.now().plusSeconds(it)) }, null).successValue()

        suspend fun positions(device: DeviceId): List<Location> =
            locations.findRecorded(device, TimeRange(clock.now().minus(Duration.ofDays(400)), clock.now().plus(Duration.ofDays(1))))

        suspend fun pairWith(other: Server) {
            val invite: PairingInvite = other.invite(listOf(other.url)).successValue()
            join(invite).successValue()
        }
    }

    /** Signs as [from], as the HTTP client does, and calls the other server's use cases as its routes do. */
    private inner class Gateway(private val from: Server) : PeerGateway {
        private var nonce = 0

        /** The other server's half of the last exchange. */
        var lastAnswer: SyncBatch? = null

        private fun signed(body: String): SignedPeerRequest {
            val input = body.encodeToByteArray()
            return SignedPeerRequest(peerLabel(from.key), clock.now(), "${from.name}-${++nonce}", input, fakeSignature(from.key, input))
        }

        private fun target(url: String, key: String): Outcome<Server> {
            val server = network[url]?.takeIf { it.online } ?: return DomainError.Network.Unreachable.asFailure()
            return if (server.key == key) Outcome.Success(server) else DomainError.ServerIdentityMismatch.asFailure()
        }

        override suspend fun join(url: String, inviterKey: String, join: PeerJoin): Outcome<PeerWelcome> =
            when (val server = target(url, inviterKey)) {
                is Outcome.Success -> server.value.accept(signed(join.toString()), join)
                is Outcome.Failure -> server
            }

        override suspend fun exchange(url: String, peerKey: String, batch: SyncBatch): Outcome<SyncBatch> {
            val server = when (val found = target(url, peerKey)) {
                is Outcome.Success -> found.value
                is Outcome.Failure -> return found
            }
            val peer = when (val known = server.answer.authenticate(signed(batch.toString()))) {
                is Outcome.Success -> known.value
                is Outcome.Failure -> return known
            }
            return server.answer(peer, batch).also { lastAnswer = (it as? Outcome.Success)?.value }
        }
    }

    private val tracker1 = aRegistration(DEVICE_A)
    private val tracker2 = aRegistration(DEVICE_B, name = "Moto de Luis")

    @Test
    fun `two servers pair with a one-time invite, each pinning the other`() = runTest {
        val home = Server("home")
        val phone = Server("phone")

        phone.pairWith(home)

        assertEquals(listOf("phone"), home.peers.all().map { it.name })
        assertEquals(listOf("home"), phone.peers.all().map { it.name })
        assertEquals(home.key, phone.peers.all().single().publicKey)
        assertEquals(phone.key, home.peers.all().single().publicKey)
        // The ticket is that server's now; a third server cannot use it.
        val invite = home.invite(listOf(home.url)).successValue()
        Server("laptop").join(invite).successValue()
        assertEquals(DomainError.PairingInvalid, Server("other").join(invite).failureError())
        // An invite read too late.
        val late = home.invite(listOf(home.url)).successValue()
        clock.advanceBy(Duration.ofMinutes(11))
        assertEquals(DomainError.PairingInvalid, Server("slow").join(late).failureError())
    }

    @Test
    fun `devices and locations go both ways, each once, and nothing comes back where it came from`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        home.send(DEVICE_A, 1, 2, 3)
        phone.enroll(tracker2)
        phone.send(DEVICE_B, 4, 5)

        phone.exchange(home.key).successValue()

        // The home server's answer carried its own tracker's positions, not the phone's back.
        assertEquals(setOf(DEVICE_A), phone.gateway.lastAnswer!!.locations.map { it.deviceId }.toSet())
        for (server in listOf(home, phone)) {
            assertEquals(listOf(1, 2, 3).map { aLocation(it, DEVICE_A).id }.toSet(), server.positions(DEVICE_A).map { it.id }.toSet())
            assertEquals(2, server.positions(DEVICE_B).size)
            assertEquals(setOf("Pixel de Ana", "Moto de Luis"), server.devices.getAllWithLastLocation().map { it.device.name }.toSet())
        }
        // The last location follows on the other side too.
        assertEquals(aLocation(3, DEVICE_A).id, phone.devices.getWithLastLocation(DEVICE_A)!!.lastLocation?.id)

        // A second exchange, with nothing new, sends nothing back, not even what each got from the other.
        phone.exchange(home.key).successValue()
        assertTrue(phone.gateway.lastAnswer!!.locations.isEmpty())
        assertEquals(3, phone.positions(DEVICE_A).size)

        // New positions arrive at the next exchange, whichever side starts it.
        home.send(DEVICE_A, 6)
        home.exchange(phone.key).successValue()
        assertEquals(4, phone.positions(DEVICE_A).size)
    }

    @Test
    fun `a server catching up gets everything in batches within one exchange`() = runTest {
        val home = Server("home", locationLimit = 3)
        val phone = Server("phone", locationLimit = 3)
        phone.pairWith(home)
        home.enroll(tracker1)
        home.send(DEVICE_A, *(1L..10L).toList().toLongArray())

        phone.exchange(home.key).successValue()

        assertEquals(10, phone.positions(DEVICE_A).size)
    }

    @Test
    fun `the latest change to a device wins on both servers, wherever it was made`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        home.approve(DEVICE_A, DeviceApproval.PENDING).successValue()
        phone.exchange(home.key).successValue()
        assertEquals(DeviceApproval.PENDING, phone.devices.get(DEVICE_A)!!.approval)

        // The owner approves it on the phone; the home server, where the tracker sends, lets it in.
        clock.advanceBy(Duration.ofMinutes(1))
        phone.approve(DEVICE_A, DeviceApproval.APPROVED).successValue()
        phone.exchange(home.key).successValue()
        assertEquals(DeviceApproval.APPROVED, home.devices.get(DEVICE_A)!!.approval)

        clock.advanceBy(Duration.ofMinutes(1))
        home.rename(DEVICE_A, "Pixel nuevo").successValue()
        home.exchange(phone.key).successValue()
        assertEquals("Pixel nuevo", phone.devices.get(DEVICE_A)!!.name)
        assertEquals(DeviceApproval.APPROVED, phone.devices.get(DEVICE_A)!!.approval)
    }

    @Test
    fun `a device removed on one server goes on the other, until it registers again`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        home.send(DEVICE_A, 1, 2)
        phone.exchange(home.key).successValue()

        clock.advanceBy(Duration.ofMinutes(1))
        phone.remove(DEVICE_A).successValue()
        phone.exchange(home.key).successValue()
        assertNull(home.devices.get(DEVICE_A))
        assertTrue(home.positions(DEVICE_A).isEmpty())
        // And it stays gone: neither side brings it back.
        home.exchange(phone.key).successValue()
        assertNull(phone.devices.get(DEVICE_A))

        clock.advanceBy(Duration.ofMinutes(1))
        home.enroll(tracker1)
        home.send(DEVICE_A, 3)
        home.exchange(phone.key).successValue()
        assertEquals(listOf(aLocation(3, DEVICE_A).id), phone.positions(DEVICE_A).map { it.id })
    }

    @Test
    fun `positions older than the receiving server keeps are left out`() = runTest {
        val home = Server("home", retentionDays = 30)
        val phone = Server("phone", retentionDays = 1)
        phone.pairWith(home)
        home.enroll(tracker1)
        home.send(DEVICE_A, 1)
        clock.advanceBy(Duration.ofDays(2))
        home.send(DEVICE_A, 2)

        phone.exchange(home.key).successValue()

        assertEquals(listOf(aLocation(2, DEVICE_A).id), phone.positions(DEVICE_A).map { it.id })
    }

    @Test
    fun `whichever side can reach the other keeps both in step, once a minute`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        phone.enroll(tracker2)
        phone.send(DEVICE_B, 1)
        // The phone is behind CGNAT: nobody reaches it, it reaches the home server.
        phone.online = false

        home.syncAll()
        assertEquals("NETWORK_UNREACHABLE", home.peers.get(phone.key)!!.sync.lastError)

        phone.syncAll()
        assertEquals(1, home.positions(DEVICE_B).size)
        // The home server cannot reach the phone, yet the two are in step: no failure to show.
        assertNull(home.peers.get(phone.key)!!.sync.lastError)
        // Done within the minute: the home server leaves it alone until the next one.
        home.send(DEVICE_A, 2)
        phone.syncAll()
        assertTrue(phone.positions(DEVICE_A).isEmpty())
        clock.advanceBy(Duration.ofMinutes(1))
        phone.syncAll()
        assertEquals(1, phone.positions(DEVICE_A).size)
    }

    @Test
    fun `a paired server can neither give a known device another key nor date its changes in the future`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        val real = home.devices.get(DEVICE_A)!!
        val peer = home.peers.get(phone.key)!!
        fun batch(vararg devices: org.privatetracker.core.domain.model.Device) =
            SyncBatch("phone", emptyList(), peer.sync.sent, peer.sync.received, devices.toList(), emptyList(), emptyList(), more = false)

        val stolen = real.copy(publicKey = fakePublicKey(DEVICE_A, generation = 2), updatedAt = clock.now().plusSeconds(60))
        home.answer(peer, batch(stolen)).successValue()
        assertEquals(real.publicKey, home.devices.get(DEVICE_A)!!.publicKey)

        // A date ten years ahead counts as now: it wins now, and loses to the owner's next change.
        clock.advanceBy(Duration.ofMinutes(1))
        val forever = real.copy(name = "Mío para siempre", updatedAt = clock.now().plus(Duration.ofDays(3650)), lastSeenAt = clock.now().plus(Duration.ofDays(1)))
        home.answer(peer, batch(forever)).successValue()
        val taken = home.devices.get(DEVICE_A)!!
        assertEquals("Mío para siempre", taken.name)
        assertEquals(clock.now(), taken.updatedAt)
        assertEquals(clock.now(), taken.lastSeenAt)
        clock.advanceBy(Duration.ofMinutes(1))
        home.rename(DEVICE_A, "Pixel de Ana").successValue()
        assertEquals("Pixel de Ana", home.devices.get(DEVICE_A)!!.name)
    }

    @Test
    fun `a tracker checking in does not undo the owner's decision made on the other server`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        phone.exchange(home.key).successValue()

        // Rejected on the phone; before the servers talk, the tracker updates its app and checks in at home.
        clock.advanceBy(Duration.ofMinutes(1))
        phone.approve(DEVICE_A, DeviceApproval.REJECTED).successValue()
        clock.advanceBy(Duration.ofMinutes(1))
        home.checkIn(tracker1.copy(appVersion = "1.0.1"))
        phone.exchange(home.key).successValue()

        for (server in listOf(home, phone)) {
            val device = server.devices.get(DEVICE_A)!!
            assertEquals(DeviceApproval.REJECTED to "1.0.1", device.approval to device.appVersion, server.name)
        }
    }

    @Test
    fun `a server restored from a backup sends everything again, then goes on`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        home.send(DEVICE_A, 1)
        phone.exchange(home.key).successValue()
        // As after home went back to a backup: the phone has more of home's numbering than home ever gave.
        phone.peers.update(home.key) { it.copy(sync = it.sync.copy(received = SyncCursor(1_000_000, 1_000_000))) }

        home.send(DEVICE_A, 2)
        phone.exchange(home.key).successValue()
        home.send(DEVICE_A, 3)
        phone.exchange(home.key).successValue()

        assertEquals(3, phone.positions(DEVICE_A).size)
        assertTrue(phone.peers.get(home.key)!!.sync.received.locations < 1_000_000)
    }

    @Test
    fun `every device from before 1_0 reaches the other server, page after page`() = runTest {
        val home = Server("home", deviceLimit = 2)
        val phone = Server("phone", deviceLimit = 2)
        phone.pairWith(home)
        val ids = (1..5).map { DeviceId.of("00000000-0000-4000-8000-00000000000$it") }
        ids.forEach { home.enroll(aRegistration(it, name = "Viejo $it")) }
        // As the migration to 1.0 leaves them: all numbered 0, no counter yet.
        home.database.useWriterConnection { connection ->
            connection.usePrepared("UPDATE devices SET change_seq = 0") { it.step() }
            connection.usePrepared("DELETE FROM sync_counters") { it.step() }
        }

        phone.exchange(home.key).successValue()

        assertEquals(ids.toSet(), phone.devices.getAllWithLastLocation().map { it.device.id }.toSet())
    }

    @Test
    fun `positions of a device not approved here are left out`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)
        home.enroll(tracker1)
        home.send(DEVICE_A, 1)
        phone.exchange(home.key).successValue()

        // The owner rejects it on the phone; home, not told yet, takes one more position from it.
        clock.advanceBy(Duration.ofMinutes(1))
        phone.approve(DEVICE_A, DeviceApproval.REJECTED).successValue()
        home.send(DEVICE_A, 2)
        home.exchange(phone.key).successValue()

        assertEquals(1, phone.positions(DEVICE_A).size)
        assertEquals(DeviceApproval.REJECTED, home.devices.get(DEVICE_A)!!.approval)
    }

    @Test
    fun `a server removed after joining cannot come back with the same ticket`() = runTest {
        val home = Server("home")
        val stranger = Server("stranger")
        val invite = home.invite(listOf(home.url)).successValue()
        stranger.join(invite).successValue()

        // The owner sees the wrong fingerprint and removes it.
        home.unpair(stranger.key).successValue()

        assertEquals(DomainError.PairingInvalid, stranger.join(invite).failureError())
        assertTrue(home.peers.all().isEmpty())
    }

    @Test
    fun `a server that removed the pairing says so, and a stranger is refused`() = runTest {
        val home = Server("home")
        val phone = Server("phone")
        phone.pairWith(home)

        home.unpair(phone.key).successValue()

        assertEquals(DomainError.PeerUnknown, phone.exchange(home.key).failureError())
        assertEquals("PEER_UNKNOWN", phone.peers.get(home.key)!!.sync.lastError)
        // A request signed with another key, claiming to be a known server.
        val laptop = Server("laptop")
        laptop.pairWith(home)
        val forged = SignedPeerRequest(peerLabel(laptop.key), clock.now(), "x", "batch".encodeToByteArray(), fakeSignature(phone.key, "batch".encodeToByteArray()))
        assertIs<DomainError.AuthenticationFailed>(home.answer.authenticate(forged).failureError())
    }
}
