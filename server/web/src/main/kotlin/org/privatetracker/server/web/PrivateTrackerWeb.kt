package org.privatetracker.server.web

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.usecase.server.AuthenticateWebSession
import org.privatetracker.core.domain.usecase.server.ChangeWebPassword
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
import org.privatetracker.core.domain.usecase.server.RemoveDevice
import org.privatetracker.core.domain.usecase.server.RotateEncryptionKey
import org.privatetracker.core.domain.usecase.server.SaveGeofence
import org.privatetracker.core.domain.usecase.server.SetDeviceApproval
import org.privatetracker.core.domain.usecase.server.SignIn
import org.privatetracker.core.domain.usecase.server.SignOut
import org.privatetracker.server.web.live.LiveAlerts
import org.privatetracker.server.web.live.liveRoutes
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.route.accountRoutes
import org.privatetracker.server.web.route.assetRoutes
import org.privatetracker.server.web.route.deviceRoutes
import org.privatetracker.server.web.route.geofenceRoutes
import org.privatetracker.server.web.route.historyRoutes
import org.privatetracker.server.web.route.mapRoutes
import org.privatetracker.server.web.route.pairingRoutes
import org.privatetracker.server.web.route.serverRoutes
import org.privatetracker.server.web.route.signInRoutes
import org.privatetracker.server.web.security.installErrorPages
import org.privatetracker.server.web.security.installWebSecurity
import java.net.URI
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Raster tiles for every map, and the attribution their license asks to show on it, with its link. */
data class MapTiles(val urlTemplate: String, val attribution: String, val attributionUrl: String?) {
    /** Where the tiles come from, for the Content-Security-Policy: scheme, host and port. */
    val origin: String
        get() = URI(urlTemplate.replace("{", "").replace("}", "")).let { "${it.scheme}://${it.authority}" }

    companion object {
        /** The phone app's default: openstreetmap.org's tiles, which browsers may load with a Referer. */
        val OPEN_STREET_MAP = MapTiles("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap", "https://www.openstreetmap.org/copyright")
    }
}

/** The certificate the web app's port presents, as the server page describes it. */
data class WebCertificate(
    /** True for the owner's own certificate (a PEM file), false for the self-signed one of the server's identity. */
    val fromFile: Boolean,
    /** SHA-256 of the certificate, as browsers show it, in pairs of hex digits. */
    val sha256: String,
    val subject: String,
    val notAfter: Instant?,
)

/** Where geofence alerts also go when no web app is open, and how the last try went. */
data class WebhookStatus(val target: String, val lastSentAt: Instant?, val lastError: String?)

/** What the web app shows about the host it runs on; the Linux server fills it in. */
data class HostStatus(
    val startedAt: Instant,
    val trackerRequests: Long,
    val trackerPort: Int,
    val webPort: Int,
    val inviteUrls: List<String>,
    val certificate: WebCertificate,
    val webhook: WebhookStatus?,
)

/**
 * Everything the web app needs, passed by constructor as [org.privatetracker.server.api.ServerDependencies]
 * is: the same domain use cases the phone's screens call, over the same server.db.
 */
class WebDependencies(
    val serverVersion: String,
    val clock: Clock,
    val serverConfig: ServerConfigRepository,
    val tiles: MapTiles,
    // Accounts and sign-in.
    val signIn: SignIn,
    val signOut: SignOut,
    val authenticate: AuthenticateWebSession,
    val changePassword: ChangeWebPassword,
    val listAccounts: ListWebAccounts,
    // Devices and their history.
    val observeDeviceOverviews: ObserveDeviceOverviews,
    val getDeviceOverviews: GetDeviceOverviews,
    val setDeviceApproval: SetDeviceApproval,
    val removeDevice: RemoveDevice,
    val getDeviceTrack: GetDeviceTrack,
    val exportDeviceHistory: ExportDeviceHistory,
    // Geofences.
    val observeGeofences: ObserveGeofences,
    val getGeofence: GetGeofence,
    val saveGeofence: SaveGeofence,
    val deleteGeofence: DeleteGeofence,
    val observeGeofenceEvents: ObserveGeofenceEvents,
    // Pairing and keys.
    val pairing: WebPairing,
    val getServerKeyFingerprint: GetServerKeyFingerprint,
    val encryptionKeys: EncryptionKeyRing,
    val rotateEncryptionKey: RotateEncryptionKey,
    /** Open pages get geofence alerts through it; the host's GeofenceAlerts publishes to it. */
    val alerts: LiveAlerts,
    val hostStatus: suspend () -> HostStatus,
    /** 32 secret bytes that sign the cookies of known browsers; kept across restarts, or those cookies stop counting. */
    val browserKey: ByteArray,
    /** Only with a certificate browsers trust: HSTS on a self-signed one would forbid clicking through its warning. */
    val sendHsts: Boolean = false,
    /** While true every request gets 503, as on the trackers' port. */
    val isShuttingDown: () -> Boolean = { false },
    /** The address a sign-in counts against. Without a proxy in front, the peer's own address. */
    val clientAddress: (ApplicationCall) -> String? = { it.request.origin.remoteAddress },
    /** How often the live stream says it is alive and checks that its session still is. */
    val liveHeartbeat: kotlin.time.Duration = 15.seconds,
)

/** Installs the web app on an engine-agnostic Ktor [Application]. */
fun Application.privateTrackerWeb(deps: WebDependencies) {
    val assets = Assets.load()
    installErrorPages(deps, assets)
    installWebSecurity(deps)
    routing {
        assetRoutes(assets)
        signInRoutes(deps, assets)
        mapRoutes(deps, assets)
        deviceRoutes(deps, assets)
        historyRoutes(deps, assets)
        pairingRoutes(deps, assets)
        geofenceRoutes(deps, assets)
        serverRoutes(deps, assets)
        accountRoutes(deps, assets)
        liveRoutes(deps)
    }
}
