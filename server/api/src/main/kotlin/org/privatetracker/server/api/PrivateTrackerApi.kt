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
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.usecase.server.GetDeviceDetail
import org.privatetracker.core.domain.usecase.server.GetDeviceOverviews
import org.privatetracker.core.domain.usecase.server.IngestLocationBatch
import org.privatetracker.core.domain.usecase.server.RegisterOrUpdateDevice
import org.privatetracker.core.protocol.v1.ErrorCode
import org.privatetracker.core.protocol.v1.ProtocolJson
import org.privatetracker.server.api.plugin.ApiException
import org.privatetracker.server.api.plugin.DEVICE_RATE_LIMIT
import org.privatetracker.server.api.plugin.installProblemResponses
import org.privatetracker.server.api.plugin.installRateLimiting
import org.privatetracker.server.api.plugin.isLoopback
import org.privatetracker.server.api.route.deviceRoutes
import org.privatetracker.server.api.route.healthRoutes
import org.privatetracker.server.api.route.locationRoutes

/** Everything the HTTP layer needs, passed by constructor so this module stays free of any DI framework. */
class ServerDependencies(
    val serverVersion: String,
    val clock: Clock,
    val serverConfig: ServerConfigRepository,
    val registerOrUpdateDevice: RegisterOrUpdateDevice,
    val ingestLocationBatch: IngestLocationBatch,
    val getDeviceOverviews: GetDeviceOverviews,
    val getDeviceDetail: GetDeviceDetail,
    val requestsPerMinute: Int = 60,
    /** Decides whether a call comes from the server itself; read endpoints depend on it. */
    val isLocalRequest: (ApplicationCall) -> Boolean = ApplicationCall::isLoopback,
    /** While true every request gets 503, so trackers keep their outbox and retry later. */
    val isShuttingDown: () -> Boolean = { false },
)

/** Installs protocol v1 on an engine-agnostic Ktor [Application]. */
fun Application.privateTrackerApi(deps: ServerDependencies) {
    install(ContentNegotiation) { json(ProtocolJson) }
    installProblemResponses()
    install(
        createApplicationPlugin("ShutdownGuard") {
            onCall {
                if (deps.isShuttingDown()) {
                    throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCode.SHUTTING_DOWN, retryAfterSeconds = 30)
                }
            }
        },
    )
    installRateLimiting(deps.requestsPerMinute)

    routing {
        healthRoutes(deps)
        rateLimit(DEVICE_RATE_LIMIT) {
            deviceRoutes(deps)
            locationRoutes(deps)
        }
    }
}
