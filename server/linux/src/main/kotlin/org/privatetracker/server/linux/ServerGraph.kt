package org.privatetracker.server.linux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.data.repository.RoomDeviceRepository
import org.privatetracker.core.data.repository.RoomGeofenceRepository
import org.privatetracker.core.data.repository.RoomLocationRepository
import org.privatetracker.core.data.repository.RoomPeerRepository
import org.privatetracker.core.data.repository.RoomSessionRepository
import org.privatetracker.core.data.repository.RoomSyncRepository
import org.privatetracker.core.data.repository.RoomTransactionRunner
import org.privatetracker.core.database.ServerDatabase
import org.privatetracker.core.database.build
import org.privatetracker.core.domain.service.SessionTracker
import org.privatetracker.core.domain.usecase.server.AcceptPeer
import org.privatetracker.core.domain.usecase.server.AnswerPeerExchange
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.AuthenticateWebSession
import org.privatetracker.core.domain.usecase.server.ChangeWebPassword
import org.privatetracker.core.domain.usecase.server.CloseAllSessions
import org.privatetracker.core.domain.usecase.server.CloseInactiveSessions
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.CreatePeerInvite
import org.privatetracker.core.domain.usecase.server.CreateWebAccount
import org.privatetracker.core.domain.usecase.server.DeleteGeofence
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.EvaluateGeofences
import org.privatetracker.core.domain.usecase.server.ExchangeWithPeer
import org.privatetracker.core.domain.usecase.server.ExportDeviceHistory
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.GetDeviceTrack
import org.privatetracker.core.domain.usecase.server.GetGeofence
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.JoinPeer
import org.privatetracker.core.domain.usecase.server.ListWebAccounts
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.ObserveGeofenceEvents
import org.privatetracker.core.domain.usecase.server.ObserveGeofences
import org.privatetracker.core.domain.usecase.server.ObservePairedDevice
import org.privatetracker.core.domain.usecase.server.ObservePeerJoined
import org.privatetracker.core.domain.usecase.server.ObservePeers
import org.privatetracker.core.domain.usecase.server.PurgeExpiredLocations
import org.privatetracker.core.domain.usecase.server.PurgeExpiredWebSessions
import org.privatetracker.core.domain.usecase.server.PurgeGeofenceEvents
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.usecase.server.RemovePeer
import org.privatetracker.core.domain.usecase.server.RemoveWebAccount
import org.privatetracker.core.domain.usecase.server.RotateEncryptionKey
import org.privatetracker.core.domain.usecase.server.SaveGeofence
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval
import org.privatetracker.core.domain.usecase.server.SignIn
import org.privatetracker.core.domain.usecase.server.SignOut
import org.privatetracker.core.domain.usecase.server.SyncBatches
import org.privatetracker.core.domain.usecase.server.SyncWithPeers
import org.privatetracker.core.domain.usecase.server.VerifyRequestSignature
import org.privatetracker.core.domain.usecase.server.WithdrawPairingInvite
import org.privatetracker.core.domain.usecase.server.WithdrawPeerInvite
import org.privatetracker.core.domain.validation.LocationValidator
import org.privatetracker.core.network.KtorPeerGateway
import org.privatetracker.core.network.OkHttpPinnedClients
import org.privatetracker.core.network.PinnedClients
import org.privatetracker.core.protocol.crypto.EcdsaP256
import org.privatetracker.server.api.auth.InMemoryNonceRegistry
import org.privatetracker.server.api.auth.InMemoryPairingTicketStore
import org.privatetracker.server.api.auth.InMemoryPeerTicketStore
import org.privatetracker.server.hostkeys.writeAtomically
import org.privatetracker.server.linux.keys.FileEncryptionKeyVault
import org.privatetracker.server.linux.keys.FileServerKeys
import org.privatetracker.server.linux.platform.AlertWebhook
import org.privatetracker.server.linux.platform.HostGeofenceAlerts
import org.privatetracker.server.linux.store.JsonFileEncryptionKeyRepository
import org.privatetracker.server.linux.store.JsonFileWebAccountRepository
import org.privatetracker.server.linux.store.JsonFileWebSessionRepository
import org.privatetracker.server.linux.store.SettingsServerConfigRepository
import org.privatetracker.server.web.live.LiveAlerts
import org.privatetracker.server.web.security.Pbkdf2PasswordHasher
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The Linux server's objects, wired by hand as Hilt wires them on a phone: the same domain and Room
 * repositories, with files where the phone has its Keystore and DataStore. Opening it opens server.db.
 */
class ServerGraph(
    val settings: ServeSettings,
    val data: DataDirectory,
    val clock: Clock = Clock.System,
    /** How it reaches paired servers; tests resolve names their own way. */
    private val peerClients: PinnedClients = OkHttpPinnedClients("PrivateTracker-Server"),
) {
    private val ids = IdGenerator.Random

    val database: ServerDatabase = ServerDatabase.build(data.database.toFile())
    private val transactions = RoomTransactionRunner(database)
    val devices = RoomDeviceRepository(database)
    private val sessions = RoomSessionRepository(database.sessions(), database.devices())
    private val locations = RoomLocationRepository(database.locations(), database.devices(), database.sessions())
    private val geofences = RoomGeofenceRepository(database, database.geofences(), database.devices())

    val config = SettingsServerConfigRepository(settings.config)
    val serverKeys = FileServerKeys(data.identity, clock)
    val encryptionKeys = EncryptionKeyRing(JsonFileEncryptionKeyRepository(data.encryptionKeys), FileEncryptionKeyVault(data.encryptionVault), serverKeys, clock)

    private val sessionTracker = SessionTracker(sessions, ids)
    /** One open invite at a time, as on a phone; `pair` watches it to tell when another `pair` replaced it. */
    val pairingTickets = InMemoryPairingTicketStore()

    /** Work that outlives a request: webhook deliveries, the web app's pairing watchdogs. [close] ends it. */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Open pages of the web app, which geofence alerts reach as they happen. */
    val liveAlerts = LiveAlerts()
    val webhook: AlertWebhook? = settings.webhook?.let { AlertWebhook(it, settings.config.serverName, background, clock) }
    private val alerts = HostGeofenceAlerts(liveAlerts, webhook)

    /** One per server: the nonces it remembers are what stops a replay. */
    private val verifySignature = VerifyRequestSignature(EcdsaP256, InMemoryNonceRegistry(clock::now), clock)

    val registerOrUpdateDevice =
        RegisterOrUpdateDevice(devices, config, sessionTracker, EcdsaP256, verifySignature, pairingTickets, transactions, clock)
    val authenticateDevice = AuthenticateDevice(devices, verifySignature)
    val ingestLocationBatch = IngestLocationBatch(
        devices, locations, sessions, config, sessionTracker, LocationValidator(clock), transactions, clock,
        EvaluateGeofences(geofences, alerts, clock), alerts,
    )
    val getDeviceOverviews = GetDeviceOverviews(devices, config, clock)
    val getDeviceDetail = GetDeviceDetail(devices, sessions, config, clock)

    val closeInactiveSessions = CloseInactiveSessions(sessions, sessionTracker, config, transactions, clock)
    val closeAllSessions = CloseAllSessions(sessions, sessionTracker, transactions, clock)
    val purgeExpiredLocations = PurgeExpiredLocations(locations, config, clock)
    val purgeGeofenceEvents = PurgeGeofenceEvents(geofences, config, clock)

    val setDeviceApproval = SetDeviceApproval(devices, transactions, clock)
    val removeDevice = RemoveDevice(devices, clock)
    val createPairingInvite = CreatePairingInvite(pairingTickets, serverKeys, config, clock)
    val observePairedDevice = ObservePairedDevice(pairingTickets, devices)
    val withdrawPairingInvite = WithdrawPairingInvite(pairingTickets)
    val getServerKeyFingerprint = GetServerKeyFingerprint(serverKeys)
    val rotateEncryptionKey = RotateEncryptionKey(encryptionKeys)

    // The web app, from 1.0.
    val observeDeviceOverviews = ObserveDeviceOverviews(devices, config, clock)
    val getDeviceTrack = GetDeviceTrack(devices, locations)
    val exportDeviceHistory = ExportDeviceHistory(devices, locations, clock)
    val observeGeofences = ObserveGeofences(geofences)
    val getGeofence = GetGeofence(geofences)
    val saveGeofence = SaveGeofence(geofences, ids, clock)
    val deleteGeofence = DeleteGeofence(geofences)
    val observeGeofenceEvents = ObserveGeofenceEvents(geofences)

    private val webAccounts = JsonFileWebAccountRepository(data.webAccounts)
    private val webSessions = JsonFileWebSessionRepository(data.webSessions)
    private val passwordHasher = Pbkdf2PasswordHasher()
    /** One limit per account, for sign-ins and for the current-password check alike. */
    private val accountAttempts = SignIn.accountAttempts()
    val signIn = SignIn(webAccounts, webSessions, passwordHasher, clock, byAccount = accountAttempts)
    val signOut = SignOut(webSessions)
    val authenticateWebSession = AuthenticateWebSession(webSessions, webAccounts, clock)
    val createWebAccount = CreateWebAccount(webAccounts, passwordHasher, clock)
    val changeWebPassword = ChangeWebPassword(webAccounts, webSessions, passwordHasher, clock, attempts = accountAttempts)
    val removeWebAccount = RemoveWebAccount(webAccounts, webSessions)
    val listWebAccounts = ListWebAccounts(webAccounts)
    val purgeExpiredWebSessions = PurgeExpiredWebSessions(webSessions, clock)

    // Paired servers, from 1.0.

    /** Where this server tells paired servers it can be reached; the running server sets it. */
    @Volatile var advertisedAddresses: suspend () -> List<String> = { emptyList() }
    private val ownAddresses: suspend () -> List<String> = { advertisedAddresses() }
    val peers = RoomPeerRepository(database)
    val peerTickets = InMemoryPeerTicketStore()
    private val peerGateway = KtorPeerGateway(peerClients, serverKeys, clock)
    private val syncBatches = SyncBatches(
        devices, RoomSyncRepository(database), peers, config, EcdsaP256, LocationValidator(clock), EvaluateGeofences(geofences, alerts, clock), clock,
    )
    val createPeerInvite = CreatePeerInvite(peerTickets, serverKeys, config, clock)
    val withdrawPeerInvite = WithdrawPeerInvite(peerTickets)
    val observePeerJoined = ObservePeerJoined(peerTickets, peers)
    val observePeers = ObservePeers(peers)
    val acceptPeer = AcceptPeer(peers, peerTickets, serverKeys, config, EcdsaP256, verifySignature, ownAddresses, clock)
    val answerPeerExchange = AnswerPeerExchange(peers, syncBatches, verifySignature, transactions, alerts, ownAddresses)
    val joinPeer = JoinPeer(peers, peerGateway, serverKeys, config, ownAddresses, clock)
    val exchangeWithPeer = ExchangeWithPeer(peers, syncBatches, peerGateway, transactions, alerts, ownAddresses, clock)
    val syncWithPeers = SyncWithPeers(peers, exchangeWithPeer, clock)
    val removePeer = RemovePeer(peers)

    /** Signs the cookies of browsers that signed in before; made once, kept with the other keys. */
    fun browserKey(): ByteArray {
        val path = data.webBrowserKey
        if (Files.exists(path)) {
            val stored = Files.readAllBytes(path)
            if (stored.size == BROWSER_KEY_BYTES) return stored
        }
        // Missing or damaged: a new one only means every browser counts as new once.
        return ByteArray(BROWSER_KEY_BYTES).also(SecureRandom()::nextBytes).also { writeAtomically(path, it) }
    }

    /** Ends the background work and closes server.db. */
    fun close() {
        background.cancel()
        (peerClients as? AutoCloseable)?.close()
        database.close()
    }

    private companion object {
        const val BROWSER_KEY_BYTES = 32
    }
}
