package org.privatetracker.server.web.security

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.section
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.page.page
import java.io.IOException
import java.net.URLEncoder
import java.time.ZoneId

/** `__Host-`: browsers keep it only from a secure page, for the whole site, and never for another host. */
const val SESSION_COOKIE = "__Host-pt-session"

/** Set by app.js, so dates read in the browser's time zone. */
const val ZONE_COOKIE = "pt-tz"

/** Thrown where a page needs a signed-in browser; answered with the sign-in page or a 401. */
class SignInRequired : RuntimeException("Sign-in required")

/** A state-changing request from another site, or one too large for any form of this app. */
class RequestRefused(val status: HttpStatusCode) : RuntimeException(status.description)

/** The session this browser's cookie opens, or [SignInRequired]. */
suspend fun ApplicationCall.requireSession(deps: WebDependencies): WebSession =
    deps.authenticate(sessionToken()) ?: throw SignInRequired()

fun ApplicationCall.sessionToken(): String? = request.cookies[SESSION_COOKIE, CookieEncoding.RAW]

fun ApplicationCall.setSessionCookie(token: String, maxAgeSeconds: Long) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = token,
            encoding = CookieEncoding.RAW,
            maxAge = maxAgeSeconds.toInt(),
            path = "/",
            secure = true,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

fun ApplicationCall.clearSessionCookie() = setSessionCookie("", 0)

/** The browser's time zone from its cookie, if it is one Java knows; else the server's. */
fun ApplicationCall.zone(): ZoneId =
    request.cookies[ZONE_COOKIE]?.takeIf { it.length <= 64 }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/** True for requests htmx makes, which want a piece of a page back. */
val ApplicationCall.isHtmx: Boolean get() = request.headers["HX-Request"] == "true"

/**
 * Headers for every answer and two checks for every request that changes something: it must come
 * from one of this app's own pages (Fetch Metadata, else Origin), which with SameSite=Strict cookies
 * shuts out cross-site request forgery, and its body must be small.
 */
fun Application.installWebSecurity(deps: WebDependencies) {
    val tiles = deps.tiles.origin
    val policy = listOf(
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self'",
        "img-src 'self' data: blob: $tiles",
        "connect-src 'self' $tiles",
        "worker-src 'self' blob:",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action 'self'",
        "frame-ancestors 'none'",
    ).joinToString("; ")
    install(
        createApplicationPlugin("WebSecurity") {
            onCall { call ->
                call.response.header("Content-Security-Policy", policy)
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header("X-Frame-Options", "DENY")
                // Not stricter: openstreetmap.org asks browsers to send a Referer with every tile.
                call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
                call.response.header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
                call.response.header("Cross-Origin-Opener-Policy", "same-origin")
                if (deps.sendHsts) call.response.header("Strict-Transport-Security", "max-age=31536000")
                if (deps.isShuttingDown()) throw RequestRefused(HttpStatusCode.ServiceUnavailable)
                val method = call.request.httpMethod
                if (method != HttpMethod.Get && method != HttpMethod.Head) {
                    if (!call.comesFromThisSite()) throw RequestRefused(HttpStatusCode.Forbidden)
                    val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (length != null && length > MAX_BODY_BYTES) throw RequestRefused(HttpStatusCode.PayloadTooLarge)
                    if (length == null && call.request.headers[HttpHeaders.TransferEncoding] != null) {
                        throw RequestRefused(HttpStatusCode.LengthRequired)
                    }
                }
            }
        },
    )
}

/** Forms and htmx carry at most a geofence with its 50 vertices; nothing near this size. */
private const val MAX_BODY_BYTES = 64 * 1024L

private fun ApplicationCall.comesFromThisSite(): Boolean {
    request.headers["Sec-Fetch-Site"]?.let { return it == "same-origin" }
    // Browsers without Fetch Metadata still send Origin with every POST.
    val origin = request.headers[HttpHeaders.Origin] ?: return false
    val host = request.headers[HttpHeaders.Host] ?: return false
    return origin.equals("https://$host", ignoreCase = true)
}

/** Error pages for people, and plain statuses for htmx and scripts. */
fun Application.installErrorPages(deps: WebDependencies, assets: Assets) {
    install(StatusPages) {
        exception<SignInRequired> { call, _ ->
            when {
                call.isHtmx -> {
                    // htmx follows this header with a full page load.
                    call.response.header("HX-Redirect", "/login")
                    call.respond(HttpStatusCode.Unauthorized)
                }
                // A page someone opened; the live stream and scripts get the plain 401 below.
                call.request.httpMethod == HttpMethod.Get && call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true -> {
                    val next = URLEncoder.encode(call.request.uri, Charsets.UTF_8)
                    call.response.header(HttpHeaders.Location, if (call.request.uri == "/") "/login" else "/login?next=$next")
                    call.respond(HttpStatusCode.SeeOther)
                }
                else -> call.respond(HttpStatusCode.Unauthorized)
            }
        }
        exception<RequestRefused> { call, cause ->
            if (cause.status == HttpStatusCode.ServiceUnavailable) call.response.header(HttpHeaders.RetryAfter, "30")
            call.respondError(deps, assets, cause.status, cause.status.description, "El servidor no aceptó la petición.")
        }
        exception<NotFoundException> { call, _ ->
            call.respondError(deps, assets, HttpStatusCode.NotFound, "No existe", "Lo que buscas no existe o ya se borró.")
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(deps, assets, HttpStatusCode.BadRequest, "Petición no válida", "Faltan datos o no tienen el formato esperado.")
        }
        // A browser that went away mid-answer (a download cancelled, a page closed): nobody to answer.
        // Ktor's closed-channel errors are IOExceptions too.
        exception<IOException> { call, cause -> call.application.log.debug("Client gone on {}: {}", call.request.uri, cause.message) }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error on ${call.request.uri}", cause)
            call.respondError(deps, assets, HttpStatusCode.InternalServerError, "Error del servidor", "Algo falló al responder. Revisa el registro del servidor.")
        }
    }
}

private suspend fun ApplicationCall.respondError(
    deps: WebDependencies,
    assets: Assets,
    status: HttpStatusCode,
    title: String,
    message: String,
) {
    if (isHtmx || request.headers[HttpHeaders.Accept]?.contains("text/html") != true) {
        respond(status)
        return
    }
    val ctx = PageContext(assets, null, deps.serverConfig.get().serverName, zone(), deps.clock.now())
    respondHtml(status) {
        page(ctx, title, section = null) {
            section("card narrow") {
                h1 { +title }
                p { +message }
                p { a(href = "/") { +"Volver al inicio" } }
            }
        }
    }
}
