package org.privatetracker.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.routing
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.usecase.server.AcceptPeer
import org.privatetracker.core.domain.usecase.server.AnswerPeerExchange
import org.privatetracker.core.domain.usecase.server.AuthenticateDevice
import org.privatetracker.core.domain.usecase.server.EncryptionKeyRing
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.server.api.plugin.ApiException
import org.privatetracker.server.api.plugin.DEVICE_RATE_LIMIT
import org.privatetracker.server.api.plugin.HEALTH_RATE_LIMIT
import org.privatetracker.server.api.plugin.PEER_RATE_LIMIT
import org.privatetracker.server.api.plugin.installProblemResponses
import org.privatetracker.server.api.plugin.installRateLimiting
import org.privatetracker.server.api.plugin.isLoopback
import org.privatetracker.server.api.route.deviceRoutes
import org.privatetracker.server.api.route.healthRoutes
import org.privatetracker.server.api.route.locationRoutes
import org.privatetracker.server.api.route.peerJoinRoutes
import org.privatetracker.server.api.route.syncRoutes

/** Everything the HTTP layer needs, passed by constructor so this module stays free of any DI framework. */
class ServerDependencies(
    val serverVersion: String,
    val clock: Clock,
    val serverConfig: ServerConfigRepository,
    /** Signs health challenges, so paired trackers can tell this server from any other. */
    val serverKeys: ServerKeys,
    /** The keys trackers seal their requests for, from 0.5 on; health hands out the current one. */
    val encryptionKeys: EncryptionKeyRing,
    val registerOrUpdateDevice: RegisterOrUpdateDevice,
    val authenticateDevice: AuthenticateDevice,
    val ingestLocationBatch: IngestLocationBatch,
    val getDeviceOverviews: GetDeviceOverviews,
    val getDeviceDetail: GetDeviceDetail,
    val requestsPerMinute: Int = 60,
    /** Decides whether a call comes from the server itself; read endpoints depend on it. */
    val isLocalRequest: (ApplicationCall) -> Boolean = ApplicationCall::isLoopback,
    /** While true every request gets 503, so trackers keep their outbox and retry later. */
    val isShuttingDown: () -> Boolean = { false },
    /** Called once per request, answered or refused, for the request count on the server screen. */
    val onRequest: () -> Unit = {},
    /**
     * From 1.0: where trackers can reach this server now, its own addresses and its relay's name for
     * it. Signed into every answer to a challenge, so paired trackers learn new ones.
     */
    val advertisedAddresses: suspend () -> List<String> = { emptyList() },
    /**
     * From 1.0: given a connection's remote address and port, the visitor a relay tunnel brought in on
     * it (the address the relay saw), or null for a connection of anyone else. Such a connection comes
     * from this machine but never counts as local, and its limits count against the visitor.
     */
    val tunneledVisitor: (address: String, port: Int) -> String? = { _, _ -> null },
    /** From 1.0: servers that read this one's invite join with it; without it, no other server can. */
    val acceptPeer: AcceptPeer? = null,
    /** From 1.0: paired servers' exchanges; without it, they get 404. */
    val answerPeerExchange: AnswerPeerExchange? = null,
)

/** Installs protocol v1 on an engine-agnostic Ktor [Application]. */
fun Application.privateTrackerApi(deps: ServerDependencies) {
    install(ContentNegotiation) { json(ProtocolJson) }
    installProblemResponses()
    install(
        createApplicationPlugin("ShutdownGuard") {
            onCall {
                deps.onRequest()
                if (deps.isShuttingDown()) {
                    throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCode.SHUTTING_DOWN, retryAfterSeconds = 30)
                }
            }
        },
    )
    installRateLimiting(deps)

    routing {
        rateLimit(HEALTH_RATE_LIMIT) {
            healthRoutes(deps)
        }
        rateLimit(PEER_RATE_LIMIT) {
            syncRoutes(deps)
        }
        rateLimit(DEVICE_RATE_LIMIT) {
            peerJoinRoutes(deps)
            deviceRoutes(deps)
            locationRoutes(deps)
        }
    }
}
