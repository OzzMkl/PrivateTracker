package org.privatetracker.server.web

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.Location
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FakeEncryptionKeyVault
import org.privatetracker.core.domain.testing.FakePasswordHasher
import org.privatetracker.core.domain.testing.FakeServerKeys
import org.privatetracker.core.domain.testing.ImmediateTransactionRunner
import org.privatetracker.core.domain.testing.InMemoryEncryptionKeyRepository
import org.privatetracker.core.domain.testing.InMemoryGeofences
import org.privatetracker.core.domain.testing.InMemoryPairingTickets
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryServerStore
import org.privatetracker.core.domain.testing.InMemoryWebAccounts
import org.privatetracker.core.domain.testing.InMemoryWebSessions
import org.privatetracker.core.domain.testing.SequentialIdGenerator
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.usecase.server.AuthenticateWebSession
import org.privatetracker.core.domain.usecase.server.ChangeWebPassword
import org.privatetracker.core.domain.usecase.server.CreatePairingInvite
import org.privatetracker.core.domain.usecase.server.CreateWebAccount
import org.privatetracker.core.domain.usecase.server.DeleteGeofence
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.ExportDeviceHistory
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.GetDeviceTrack
import org.privatetracker.core.domain.usecase.server.GetGeofence
import org.privatetracker.core.domain.usecase.server.GetServerKeyFingerprint
import org.privatetracker.core.domain.usecase.server.ListWebAccounts
import org.privatetracker.core.domain.usecase.server.ObserveDeviceOverviews
import org.privatetracker.core.domain.usecase.server.ObserveGeofenceEvents
import org.privatetracker.core.domain.usecase.server.ObserveGeofences
import org.privatetracker.core.domain.usecase.server.ObservePairedDevice
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.usecase.server.RotateEncryptionKey
import org.privatetracker.core.domain.usecase.server.SaveGeofence
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval
import org.privatetracker.core.domain.usecase.server.SignIn
import org.privatetracker.core.domain.usecase.server.SignOut
import org.privatetracker.core.domain.usecase.server.WithdrawPairingInvite
import org.privatetracker.server.web.live.LiveAlerts
import org.privatetracker.server.web.security.SESSION_COOKIE
import kotlin.time.Duration.Companion.milliseconds

const val PASSWORD = "caballo bateria grapa correcta"

/** The web app over in-memory storage, with the domain's own use cases; the clock stands still at T0. */
class WebTestbed(config: ServerConfig = ServerConfig(serverName = "Casa")) {
    val clock = FakeClock(T0.plusSeconds(60))
    val store = InMemoryServerStore()
    val geofences = InMemoryGeofences(store)
    val configRepository = InMemoryServerConfigRepository(config)
    val accounts = InMemoryWebAccounts()
    val sessions = InMemoryWebSessions()
    val hasher = FakePasswordHasher()
    val tickets = InMemoryPairingTickets()
    val alerts = LiveAlerts()
    val scope = CoroutineScope(SupervisorJob())
    var inviteUrls = listOf("https://192.168.1.20:8787")
    private val ids = SequentialIdGenerator()
    private val ring = EncryptionKeyRing(InMemoryEncryptionKeyRepository(), FakeEncryptionKeyVault(), FakeServerKeys(), clock)

    val deps = WebDependencies(
        serverVersion = "test",
        clock = clock,
        serverConfig = configRepository,
        tiles = MapTiles.OPEN_STREET_MAP,
        signIn = SignIn(accounts, sessions, hasher, clock),
        signOut = SignOut(sessions),
        authenticate = AuthenticateWebSession(sessions, accounts, clock),
        changePassword = ChangeWebPassword(accounts, sessions, hasher, clock),
        listAccounts = ListWebAccounts(accounts),
        observeDeviceOverviews = ObserveDeviceOverviews(store, configRepository, clock),
        getDeviceOverviews = GetDeviceOverviews(store, configRepository, clock),
        setDeviceApproval = SetDeviceApproval(store, ImmediateTransactionRunner),
        removeDevice = RemoveDevice(store),
        getDeviceTrack = GetDeviceTrack(store, store),
        exportDeviceHistory = ExportDeviceHistory(store, store, clock),
        observeGeofences = ObserveGeofences(geofences),
        getGeofence = GetGeofence(geofences),
        saveGeofence = SaveGeofence(geofences, ids, clock),
        deleteGeofence = DeleteGeofence(geofences),
        observeGeofenceEvents = ObserveGeofenceEvents(geofences),
        pairing = WebPairing(
            CreatePairingInvite(tickets, FakeServerKeys(), configRepository, clock),
            ObservePairedDevice(tickets, store),
            WithdrawPairingInvite(tickets),
            tickets,
            { inviteUrls },
            clock,
            scope,
            checkEvery = 50.milliseconds,
        ),
        getServerKeyFingerprint = GetServerKeyFingerprint(FakeServerKeys()),
        encryptionKeys = ring,
        rotateEncryptionKey = RotateEncryptionKey(ring),
        alerts = alerts,
        hostStatus = {
            HostStatus(T0, 42, 8787, 8788, inviteUrls, WebCertificate(false, "AB:CD", "PrivateTracker Server", null), null)
        },
        browserKey = ByteArray(32) { it.toByte() },
    )

    fun account(name: String = "ana", password: String = PASSWORD) = runBlocking { CreateWebAccount(accounts, hasher, clock)(name, password) }

    fun device(device: Device, vararg locations: Location) = runBlocking {
        store.insert(device)
        locations.forEach { store.insertIfAbsent(it, null) }
        locations.maxByOrNull { it.recordedAt }?.let { store.setLastLocation(device.id, it.id) }
    }

    fun run(block: suspend ApplicationTestBuilder.() -> Unit) {
        try {
            testApplication {
                application { privateTrackerWeb(deps) }
                block()
            }
        } finally {
            scope.cancel()
        }
    }
}

/** A client that follows no redirects, so tests see each 303 and where it points. */
fun ApplicationTestBuilder.browser(): HttpClient = createClient { followRedirects = false }

/** What a browser adds to a form it posts from one of the app's own pages. */
fun HttpRequestBuilder.fromThisSite() {
    header("Sec-Fetch-Site", "same-origin")
}

fun HttpRequestBuilder.signedIn(cookie: String) {
    header(HttpHeaders.Cookie, "$SESSION_COOKIE=$cookie")
}

fun HttpRequestBuilder.page() {
    header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
}

/** Signs in through the form and returns the session cookie's value. */
suspend fun HttpClient.signIn(name: String = "ana", password: String = PASSWORD): String {
    val response = submitForm("/login", Parameters.build { append("name", name); append("password", password) }) { fromThisSite() }
    return sessionCookie(response) ?: error("No session cookie: ${response.status}")
}

fun sessionCookie(response: HttpResponse): String? =
    response.headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$SESSION_COOKIE=") }
        ?.substringAfter("=")?.substringBefore(";")?.takeIf { it.isNotEmpty() }

suspend fun HttpClient.getPage(path: String, cookie: String?): HttpResponse = get(path) {
    page()
    cookie?.let { signedIn(it) }
}
