package org.privatetracker.server.linux

import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.DeviceStatus
import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.server.api.ServerDependencies
import org.privatetracker.server.api.privateTrackerApi
import org.privatetracker.server.linux.admin.AdminConnection
import org.privatetracker.server.linux.admin.AdminHandler
import org.privatetracker.server.linux.admin.AdminReply
import org.privatetracker.server.linux.admin.AdminRequest
import org.privatetracker.server.linux.admin.AdminServer
import org.privatetracker.server.linux.admin.AlreadyRunningException
import org.privatetracker.server.linux.admin.DeviceRow
import org.privatetracker.server.linux.admin.UserRow
import org.privatetracker.server.linux.keys.ReloadingKeyManager
import org.privatetracker.server.linux.keys.commonName
import org.privatetracker.server.linux.keys.sha256
import org.privatetracker.server.linux.platform.hostAddresses
import org.privatetracker.server.linux.platform.inviteUrls
import org.privatetracker.server.linux.platform.runsInContainer
import org.privatetracker.server.netty.tlsServer
import org.privatetracker.server.web.HostStatus
import org.privatetracker.server.web.WebCertificate
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.WebPairing
import org.privatetracker.server.web.privateTrackerWeb
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration

/**
 * The running server: the API on Netty over TLS with the identity key, from 1.0 the web app on a port
 * of its own, the admin socket for the command line, and the daily maintenance a phone leaves to
 * WorkManager. [stop] is what SIGTERM runs.
 */
class LinuxServer(private val graph: ServerGraph, private val version: String) : AdminHandler {
    private val log = LoggerFactory.getLogger("Server")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admin = AdminServer(graph.data.adminSocket, this)
    private val requests = AtomicLong()
    private val startedAt: Instant = graph.clock.now()

    @Volatile private var shuttingDown = false
    private var server: EmbeddedServer<*, *>? = null
    private var web: EmbeddedServer<*, *>? = null
    private var lock: AutoCloseable? = null

    /** What the web port presents now; the owner's PEM files may be renewed while the server runs. */
    private var webCertificate: () -> X509Certificate = { graph.serverKeys.certificate() }

    /**
     * Throws when the data directory is in use, or the port, the keys or the admin socket are not
     * available; nothing is left running then.
     */
    fun start() {
        val config = graph.settings.config
        // First: no second server may touch this server.db or make a key here, even one starting at the same moment.
        lock = graph.data.lock() ?: throw AlreadyRunningException(graph.data.adminSocket)
        try {
            admin.start()
            // Sessions a crash left open end at their last activity, not now.
            runBlocking { graph.closeInactiveSessions() }
            // Without its key the server has no TLS, and it never serves in the clear instead.
            val keyManager = graph.serverKeys.keyManager()
            val dependencies = ServerDependencies(
                serverVersion = version,
                clock = graph.clock,
                serverConfig = graph.config,
                serverKeys = graph.serverKeys,
                encryptionKeys = graph.encryptionKeys,
                registerOrUpdateDevice = graph.registerOrUpdateDevice,
                authenticateDevice = graph.authenticateDevice,
                ingestLocationBatch = graph.ingestLocationBatch,
                getDeviceOverviews = graph.getDeviceOverviews,
                getDeviceDetail = graph.getDeviceDetail,
                // On a phone loopback is the owner's own device; on a Linux host it is every account and
                // service on it. The read endpoints stay shut until the web app brings real sign-in (hito 14).
                isLocalRequest = { false },
                isShuttingDown = { shuttingDown },
                onRequest = { requests.incrementAndGet() },
            )
            server = tlsServer(config.port, config.bindAddress, keyManager) { privateTrackerApi(dependencies) }.start(wait = false)
            graph.settings.web?.let { settings -> web = startWeb(settings, keyManager) }
        } catch (e: Throwable) {
            // Errors too: a native library that will not load must not leave the socket and the lock behind.
            server?.stop(0, 0)
            admin.stop()
            lock?.close()
            throw e
        }
        scope.launch { maintain() }
        val fingerprint = runBlocking { graph.getServerKeyFingerprint() }
        log.info("PrivateTracker Server {} listening on {}:{}, key fingerprint {}", version, config.bindAddress, config.port, fingerprint)
        val urls = currentInviteUrls()
        if (urls.isEmpty()) {
            log.warn("No address to give trackers: set PRIVATETRACKER_PUBLIC_URLS (or --public-url) to the URL phones can reach")
        } else {
            log.info("Trackers reach it at {}", urls.joinToString(", "))
        }
        graph.settings.webhook?.takeIf { it.url.scheme == "http" && it.token != null }?.let {
            log.warn("The alert webhook is http://: its token travels in the clear. Use https:// unless the network between is yours alone.")
        }
        graph.settings.web?.let { settings ->
            val webUrls = inviteUrls(emptyList(), settings.port, settings.bindAddress, runsInContainer(), hostAddresses())
            log.info("Web app on port {}{}", settings.port, if (webUrls.isEmpty()) "" else ": " + webUrls.joinToString(", "))
            if (runBlocking { graph.listWebAccounts() }.isEmpty()) {
                log.info("No web account yet: create one with `privatetracker-server users add <name>`")
            }
        }
    }

    /**
     * The web app on its own port, so trackers keep the identity key they pinned on theirs while the
     * browser may get a certificate it trusts. Without the owner's PEM files it shows the identity's
     * self-signed certificate; HSTS only goes with the owner's own one.
     */
    private fun startWeb(settings: WebSettings, identityKeyManager: X509ExtendedKeyManager): EmbeddedServer<*, *> {
        val keyManager = settings.certificate?.let { files ->
            ReloadingKeyManager(files).also { reloading -> webCertificate = { reloading.pair.chain.first() } }
        } ?: identityKeyManager
        val dependencies = WebDependencies(
            serverVersion = version,
            clock = graph.clock,
            serverConfig = graph.config,
            tiles = graph.settings.tiles,
            signIn = graph.signIn,
            signOut = graph.signOut,
            authenticate = graph.authenticateWebSession,
            changePassword = graph.changeWebPassword,
            listAccounts = graph.listWebAccounts,
            observeDeviceOverviews = graph.observeDeviceOverviews,
            getDeviceOverviews = graph.getDeviceOverviews,
            setDeviceApproval = graph.setDeviceApproval,
            removeDevice = graph.removeDevice,
            getDeviceTrack = graph.getDeviceTrack,
            exportDeviceHistory = graph.exportDeviceHistory,
            observeGeofences = graph.observeGeofences,
            getGeofence = graph.getGeofence,
            saveGeofence = graph.saveGeofence,
            deleteGeofence = graph.deleteGeofence,
            observeGeofenceEvents = graph.observeGeofenceEvents,
            pairing = WebPairing(
                graph.createPairingInvite,
                graph.observePairedDevice,
                graph.withdrawPairingInvite,
                graph.pairingTickets,
                ::currentInviteUrls,
                graph.clock,
                graph.background,
            ),
            getServerKeyFingerprint = graph.getServerKeyFingerprint,
            encryptionKeys = graph.encryptionKeys,
            rotateEncryptionKey = graph.rotateEncryptionKey,
            alerts = graph.liveAlerts,
            hostStatus = ::hostStatus,
            browserKey = graph.browserKey(),
            sendHsts = settings.certificate != null,
            isShuttingDown = { shuttingDown },
        )
        return tlsServer(settings.port, settings.bindAddress, keyManager) { privateTrackerWeb(dependencies) }.start(wait = false)
    }

    private fun hostStatus(): HostStatus {
        val certificate = webCertificate()
        return HostStatus(
            startedAt = startedAt,
            trackerRequests = requests.get(),
            trackerPort = graph.settings.config.port,
            webPort = graph.settings.web?.port ?: 0,
            inviteUrls = currentInviteUrls(),
            certificate = WebCertificate(
                fromFile = graph.settings.web?.certificate != null,
                sha256 = certificate.sha256(),
                subject = certificate.commonName(),
                // The identity's certificate says 9999: it has no expiry, so none is shown.
                notAfter = certificate.notAfter.toInstant().takeIf { it.isBefore(Instant.parse("9000-01-01T00:00:00Z")) },
            ),
            webhook = graph.webhook?.status,
        )
    }

    /** Answers 503 during the grace period, so trackers keep their outbox, then closes every session. */
    fun stop() {
        if (shuttingDown) return
        shuttingDown = true
        log.info("Stopping")
        scope.cancel()
        admin.stop()
        listOfNotNull(web, server).forEach {
            try {
                it.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000)
            } catch (e: Exception) {
                log.warn("Netty did not stop cleanly: {}", e.message)
            }
        }
        runBlocking { graph.closeAllSessions() }
        graph.close()
        lock?.close()
        log.info("Stopped")
    }

    /**
     * Daily, from the start: what MaintenanceWorker does on a phone. A pass that fails (a locked database,
     * a full disk) is tried again within the hour, as WorkManager would retry, instead of ending the loop.
     */
    private suspend fun maintain() {
        while (true) {
            val next = try {
                val closed = graph.closeInactiveSessions()
                val purged = graph.purgeExpiredLocations()
                val purgedEvents = graph.purgeGeofenceEvents()
                graph.purgeExpiredWebSessions()
                val key = try {
                    graph.encryptionKeys.current().key.id
                } catch (e: DeviceKeyException) {
                    log.error("Encryption key unavailable", e)
                    null
                }
                log.info("Maintenance: closed {} sessions, purged {} locations and {} geofence events, encryption key {}", closed, purged, purgedEvents, key)
                if (key == null) 1.hours else 1.days
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Maintenance failed; trying again in an hour", e)
                1.hours
            }
            delay(next)
        }
    }

    private fun currentInviteUrls(): List<String> {
        val config = graph.settings.config
        return inviteUrls(graph.settings.publicUrls, config.port, config.bindAddress, runsInContainer(), hostAddresses())
    }

    // The command line's requests.

    override suspend fun handle(request: AdminRequest, connection: AdminConnection) {
        when (request) {
            AdminRequest.Status -> connection.send(status())
            AdminRequest.Devices -> connection.send(AdminReply.Devices(overviews().map { it.toRow() }))
            is AdminRequest.Approve -> connection.send(approve(request))
            is AdminRequest.Reject -> connection.send(decide(request.device, DeviceApproval.REJECTED))
            is AdminRequest.Remove -> connection.send(remove(request.device))
            AdminRequest.RotateKey -> connection.send(
                when (val rotated = graph.rotateEncryptionKey()) {
                    is Outcome.Success -> AdminReply.Done(encryptionKeyId = rotated.value.id)
                    is Outcome.Failure -> AdminReply.Error("No se pudo rotar la clave de cifrado: el archivo de claves no responde")
                },
            )
            AdminRequest.Pair -> pair(connection)
            AdminRequest.Users -> connection.send(users())
            is AdminRequest.AddUser -> connection.send(
                when (val created = graph.createWebAccount(request.name, request.password)) {
                    is Outcome.Success -> users()
                    is Outcome.Failure -> AdminReply.Error(accountError(created.error))
                },
            )
            is AdminRequest.SetPassword -> connection.send(
                when (val changed = graph.changeWebPassword(request.name, request.password)) {
                    is Outcome.Success -> {
                        // The command line keeps no browser session: the one the change opens ends at once.
                        graph.signOut(changed.value.token)
                        users()
                    }
                    is Outcome.Failure -> AdminReply.Error(accountError(changed.error))
                },
            )
            is AdminRequest.RemoveUser -> connection.send(
                when (val removed = graph.removeWebAccount(request.name)) {
                    is Outcome.Success -> users()
                    is Outcome.Failure -> AdminReply.Error(accountError(removed.error))
                },
            )
        }
    }

    private suspend fun users() = AdminReply.Users(graph.listWebAccounts().map { UserRow(it.name, it.createdAt.toEpochMilli()) })

    private fun accountError(error: DomainError): String = when (error) {
        DomainError.WebAccountNotFound -> "No hay ninguna cuenta con ese nombre. Mira la lista con: privatetracker-server users"
        is DomainError.Validation -> error.violations.joinToString(" ") { violation ->
            when (violation.field to violation.rule) {
                "name" to FieldViolation.TAKEN -> "Ya hay una cuenta con ese nombre."
                "name" to FieldViolation.REQUIRED -> "Falta el nombre."
                "name" to FieldViolation.TOO_LONG -> "El nombre admite hasta ${WebAccount.NAME_MAX_LENGTH} caracteres."
                "name" to FieldViolation.INVALID_FORMAT -> "El nombre solo admite letras sin acentos, números, punto, guion y guion bajo, y empieza con letra o número."
                "password" to FieldViolation.TOO_SHORT -> "La contraseña necesita al menos ${WebAccount.PASSWORD_MIN_LENGTH} caracteres; una frase de varias palabras sirve."
                "password" to FieldViolation.TOO_LONG -> "La contraseña admite hasta ${WebAccount.PASSWORD_MAX_LENGTH} caracteres."
                "password" to FieldViolation.TOO_COMMON -> "Esa contraseña es de las primeras que alguien probaría; elige otra."
                else -> "${violation.field}: ${violation.rule}"
            }
        }
        else -> "No se pudo: ${error.code}"
    }

    private suspend fun status(): AdminReply.Status {
        val overviews = overviews()
        val key = try {
            graph.encryptionKeys.current().key
        } catch (e: DeviceKeyException) {
            null
        }
        val config = graph.config.get()
        return AdminReply.Status(
            serverName = config.serverName,
            version = version,
            port = config.port,
            startedAtMillis = startedAt.toEpochMilli(),
            requests = requests.get(),
            fingerprint = graph.getServerKeyFingerprint(),
            inviteUrls = currentInviteUrls(),
            devices = overviews.size,
            pending = overviews.count { it.device.awaitsApproval },
            online = overviews.count { it.status == DeviceStatus.ONLINE },
            encryptionKeyId = key?.id,
            encryptionKeyUntilMillis = key?.useUntil?.toEpochMilli(),
            webPort = graph.settings.web?.port,
            webCertificateSha256 = graph.settings.web?.let { runCatching { webCertificate().sha256() }.getOrNull() },
            webAccounts = graph.listWebAccounts().size,
        )
    }

    private suspend fun approve(request: AdminRequest.Approve): AdminReply {
        if (!request.allPending) return decide(request.device ?: return AdminReply.Error("Falta el dispositivo"), DeviceApproval.APPROVED)
        val pending = overviews().map { it.device }.filter { it.awaitsApproval }
        pending.forEach { graph.setDeviceApproval(it.id, DeviceApproval.APPROVED) }
        return AdminReply.Done(rows(pending.map { it.id.value }))
    }

    private suspend fun decide(reference: String, approval: DeviceApproval): AdminReply {
        val device = when (val found = find(reference)) {
            is Found.One -> found.device
            is Found.Problem -> return found.reply
        }
        return when (graph.setDeviceApproval(device.id, approval)) {
            is Outcome.Success -> AdminReply.Done(rows(listOf(device.id.value)))
            is Outcome.Failure -> AdminReply.Error("El dispositivo ya no existe")
        }
    }

    private suspend fun remove(reference: String): AdminReply {
        val device = when (val found = find(reference)) {
            is Found.One -> found.device
            is Found.Problem -> return found.reply
        }
        val row = rows(listOf(device.id.value))
        return when (graph.removeDevice(device.id)) {
            is Outcome.Success -> AdminReply.Done(row)
            is Outcome.Failure -> AdminReply.Error("El dispositivo ya no existe")
        }
    }

    /**
     * Shows an invite until a tracker uses it, it expires, another `pair` replaces it (one ticket is open
     * at a time) or the client hangs up. A used ticket stays, so a tracker that lost the answer can
     * register again; any other ending withdraws it.
     */
    private suspend fun pair(connection: AdminConnection) {
        val urls = currentInviteUrls()
        if (urls.isEmpty()) {
            val hint = if (runsInContainer()) {
                "Dentro de un contenedor define PRIVATETRACKER_PUBLIC_URLS con la URL que ven los teléfonos, p. ej. https://192.168.1.20:8787"
            } else {
                "Este equipo no tiene direcciones de red local; arranca el servidor con --public-url https://<dirección>:${graph.settings.config.port}"
            }
            connection.send(AdminReply.Error("No hay ninguna dirección que dar a los Trackers. $hint"))
            return
        }
        val invite = when (val created = graph.createPairingInvite(urls)) {
            is Outcome.Success -> created.value
            is Outcome.Failure -> {
                connection.send(AdminReply.Error(if (created.error is DomainError.DeviceKeyUnavailable) "La clave del servidor no responde" else "No se pudo crear la invitación"))
                return
            }
        }
        connection.send(
            AdminReply.Invite(
                uri = PairingUri.format(invite),
                urls = invite.serverUrls,
                fingerprint = keyFingerprint(invite.serverKey),
                expiresAtMillis = invite.expiresAt.toEpochMilli(),
            ),
        )
        val hangUp = connection.hangUp()
        val remaining = Duration.between(graph.clock.now(), invite.expiresAt).coerceAtLeast(Duration.ZERO).toKotlinDuration()
        val paired = scope.async { withTimeoutOrNull(remaining) { graph.observePairedDevice(invite.ticketId).filterNotNull().first() } }
        val replaced = scope.async { graph.pairingTickets.observe(invite.ticketId).first { it == null } }
        val ending = try {
            select<PairingEnd> {
                paired.onAwait { device -> device?.let(PairingEnd::Paired) ?: PairingEnd.Expired }
                replaced.onAwait { PairingEnd.Replaced }
                hangUp.onAwait { PairingEnd.HungUp }
            }
        } catch (e: Exception) {
            // Failing or stopping: the QR must not stay valid with nobody watching it.
            graph.withdrawPairingInvite(invite.ticketId)
            throw e
        } finally {
            paired.cancel()
            replaced.cancel()
        }
        when (ending) {
            is PairingEnd.Paired -> rows(listOf(ending.device.id.value)).firstOrNull()?.let { connection.send(AdminReply.Paired(it)) }
            PairingEnd.Expired -> {
                graph.withdrawPairingInvite(invite.ticketId)
                connection.send(AdminReply.Expired)
            }
            PairingEnd.Replaced -> connection.send(AdminReply.Error("Otro «pair» generó un código nuevo; este ya no sirve."))
            PairingEnd.HungUp -> graph.withdrawPairingInvite(invite.ticketId)
        }
    }

    private sealed interface PairingEnd {
        data class Paired(val device: Device) : PairingEnd
        data object Expired : PairingEnd
        data object Replaced : PairingEnd
        data object HungUp : PairingEnd
    }

    // Devices, as the command line names them.

    private suspend fun overviews(): List<DeviceOverview> = graph.getDeviceOverviews()

    private suspend fun rows(ids: List<String>): List<DeviceRow> {
        val byId = overviews().associateBy { it.device.id.value }
        return ids.mapNotNull { byId[it]?.toRow() }
    }

    private sealed interface Found {
        data class One(val device: Device) : Found
        data class Problem(val reply: AdminReply.Error) : Found
    }

    /**
     * By whole id, else by exact name or the start of an id (4 characters or more), ignoring case. A name
     * never wins over an id: anyone on the network can name a pending device after another one's id.
     */
    private suspend fun find(reference: String): Found {
        val wanted = reference.trim()
        val all = overviews().map { it.device }
        all.firstOrNull { it.id.value.equals(wanted, ignoreCase = true) }?.let { return Found.One(it) }
        val named = all.filter { it.name.equals(wanted, ignoreCase = true) }
        val prefixed = if (wanted.length >= 4) all.filter { it.id.value.startsWith(wanted, ignoreCase = true) } else emptyList()
        val matches = (named + prefixed).distinctBy { it.id }
        return when (matches.size) {
            1 -> Found.One(matches.single())
            0 -> Found.Problem(AdminReply.Error("No hay ningún dispositivo «$wanted». Mira la lista con: privatetracker-server devices"))
            else -> Found.Problem(
                AdminReply.Error("«$wanted» puede ser ${matches.joinToString(", ") { "${printable(it.name)} (${it.id.value.take(8)})" }}; usa el id completo"),
            )
        }
    }

    private fun DeviceOverview.toRow() = DeviceRow(
        id = device.id.value,
        name = device.name,
        platform = device.platform.name,
        appVersion = device.appVersion,
        approval = device.approval.name,
        status = status.name,
        lastSeenAtMillis = device.lastSeenAt?.toEpochMilli(),
        fingerprint = device.publicKey?.let(::keyFingerprint),
    )
}
