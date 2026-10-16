package org.privatetracker.server.web.route

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.MAIN
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.section
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.core.domain.usecase.server.normalizeAccountName
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.page.violationText
import org.privatetracker.server.web.security.KnownBrowsers
import org.privatetracker.server.web.security.clearSessionCookie
import org.privatetracker.server.web.security.knownBrowserCookie
import org.privatetracker.server.web.security.requireSession
import org.privatetracker.server.web.security.sessionToken
import org.privatetracker.server.web.security.setKnownBrowserCookie
import org.privatetracker.server.web.security.setSessionCookie

/**
 * Where to go after signing in: only a path of this site. Visible ASCII only and no backslash at all:
 * browsers drop tabs and newlines from a URL and read `\` as `/`, so `/<tab>/host` or `/\host` would
 * become another site's `//host`.
 */
internal fun safeNext(raw: String?): String =
    raw?.takeIf { next ->
        next.length in 1..512 && next.all { it in '!'..'~' && it != '\\' } &&
            next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/login")
    } ?: "/"

/** Marks this browser as one [account] signs in from, for [KnownBrowsers]. */
private suspend fun remember(deps: WebDependencies, browsers: KnownBrowsers, call: ApplicationCall, account: String) {
    deps.listAccounts().firstOrNull { it.name == account }?.let { call.setKnownBrowserCookie(browsers.value(it)) }
}

internal fun Route.signInRoutes(deps: WebDependencies, assets: Assets) {
    val knownBrowsers = KnownBrowsers(deps.browserKey)
    get("/login") {
        if (deps.authenticate(call.sessionToken()) != null) return@get call.seeOther(safeNext(call.parameters["next"]))
        call.noStore()
        val ctx = call.pageContext(deps, assets, null)
        val noAccounts = deps.listAccounts().isEmpty()
        call.respondHtml { page(ctx, "Entrar", section = null) { signInForm(ctx, call.parameters["next"], error = null, noAccounts = noAccounts) } }
    }

    post("/login") {
        val form = call.receiveParameters()
        val next = form["next"]
        val name = form["name"].orEmpty()
        val cookie = call.knownBrowserCookie()
        val known = cookie != null &&
            deps.listAccounts().firstOrNull { it.name == normalizeAccountName(name) }?.let { knownBrowsers.isKnown(cookie, it) } == true
        val result = deps.signIn(name, form["password"].orEmpty(), deps.clientAddress(call), knownBrowser = known)
        if (result is Outcome.Success) {
            call.setSessionCookie(result.value.token, WebSession.LIFETIME.seconds)
            remember(deps, knownBrowsers, call, result.value.session.account)
            return@post call.seeOther(safeNext(next))
        }
        val error = (result as Outcome.Failure).error
        val (status, message) = when (error) {
            is DomainError.TooManyAttempts -> {
                call.response.header(HttpHeaders.RetryAfter, error.retryAfterSeconds.toString())
                HttpStatusCode.TooManyRequests to "Demasiados intentos. Vuelve a probar en ${(error.retryAfterSeconds + 59) / 60} min."
            }
            else -> HttpStatusCode.Unauthorized to "Nombre o contraseña incorrectos."
        }
        call.noStore()
        val ctx = call.pageContext(deps, assets, null)
        call.respondHtml(status) { page(ctx, "Entrar", section = null) { signInForm(ctx, next, message, noAccounts = false, name = form["name"]) } }
    }

    post("/logout") {
        call.sessionToken()?.let { deps.signOut(it) }
        call.clearSessionCookie()
        call.seeOther("/login")
    }

    // For app.js, which cannot see why its live stream failed: 204 while signed in, else 401.
    get("/session") {
        call.noStore()
        call.requireSession(deps)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun MAIN.signInForm(ctx: PageContext, next: String?, error: String?, noAccounts: Boolean, name: String? = null) {
    section("card narrow signin") {
        h1 { +"Entrar a ${ctx.serverName}" }
        if (noAccounts) {
            div("notice") {
                p { +"Todavía no hay cuentas. Crea la primera en el equipo del servidor:" }
                p { code { +"privatetracker-server users add <nombre>" } }
                p { +"En Docker: docker exec -it <contenedor> privatetracker-server users add <nombre>" }
            }
        }
        error?.let { div("error") { attributes["role"] = "alert"; +it } }
        form(action = "/login", method = FormMethod.post, classes = "stack") {
            next?.let { hiddenInput(name = "next") { value = it } }
            label {
                +"Nombre"
                input(InputType.text, name = "name") {
                    id = "name"
                    attributes["autocomplete"] = "username"
                    attributes["autocapitalize"] = "none"
                    attributes["spellcheck"] = "false"
                    required = true
                    autoFocus = name == null
                    name?.let { value = it }
                }
            }
            label {
                +"Contraseña"
                input(InputType.password, name = "password") {
                    attributes["autocomplete"] = "current-password"
                    required = true
                    autoFocus = name != null
                }
            }
            button(type = ButtonType.submit, classes = "primary") { +"Entrar" }
        }
    }
}

internal fun Route.accountRoutes(deps: WebDependencies, assets: Assets) {
    val knownBrowsers = KnownBrowsers(deps.browserKey)
    get("/account") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val done = call.parameters["done"] == "password"
        call.respondHtml { page(ctx, "Tu cuenta", section = null) { accountPage(ctx, messages = emptyList(), done = done) } }
    }

    post("/account/password") {
        val session = call.requireSession(deps)
        val form = call.receiveParameters()
        val current = form["current"].orEmpty()
        val fresh = form["password"].orEmpty()
        val messages = if (fresh != form["repeat"]) {
            listOf("Las dos contraseñas nuevas no coinciden.")
        } else {
            when (val changed = deps.changePassword(session.account, fresh, currentPassword = current)) {
                is Outcome.Success -> {
                    // Every other session ended; this browser stays in with the new one, and stays known.
                    // A 303, so reloading the next page does not post the old password again.
                    call.setSessionCookie(changed.value.token, WebSession.LIFETIME.seconds)
                    remember(deps, knownBrowsers, call, session.account)
                    return@post call.seeOther("/account?done=password")
                }
                is Outcome.Failure -> when (val error = changed.error) {
                    is DomainError.Validation -> error.violations.map(::violationText)
                    DomainError.SignInFailed -> listOf("La contraseña actual no es correcta.")
                    is DomainError.TooManyAttempts -> listOf("Demasiados intentos. Vuelve a probar en ${(error.retryAfterSeconds + 59) / 60} min.")
                    else -> listOf("No se pudo cambiar la contraseña.")
                }
            }
        }
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        call.respondHtml(HttpStatusCode.UnprocessableEntity) { page(ctx, "Tu cuenta", section = null) { accountPage(ctx, messages, done = false) } }
    }
}

private fun MAIN.accountPage(ctx: PageContext, messages: List<String>, done: Boolean) {
    section("card narrow") {
        h1 { +"Tu cuenta: ${ctx.account}" }
        p("muted") { +"Al cambiar la contraseña se cierran las sesiones de esta cuenta en los demás navegadores." }
        if (done) div("success") { attributes["role"] = "status"; +"Contraseña cambiada. Las demás sesiones se cerraron." }
        messages.forEach { div("error") { attributes["role"] = "alert"; +it } }
        form(action = "/account/password", method = FormMethod.post, classes = "stack") {
            // Lets password managers file the new password under the right account.
            input(InputType.text, name = "username") {
                attributes["autocomplete"] = "username"
                attributes["hidden"] = ""
                value = ctx.account.orEmpty()
                readonly = true
            }
            label {
                +"Contraseña actual"
                input(InputType.password, name = "current") {
                    attributes["autocomplete"] = "current-password"
                    required = true
                }
            }
            label {
                +"Contraseña nueva (15 caracteres o más)"
                input(InputType.password, name = "password") {
                    attributes["autocomplete"] = "new-password"
                    attributes["minlength"] = "15"
                    required = true
                }
            }
            label {
                +"Repite la contraseña nueva"
                input(InputType.password, name = "repeat") {
                    attributes["autocomplete"] = "new-password"
                    required = true
                }
            }
            button(type = ButtonType.submit, classes = "primary") { +"Cambiar contraseña" }
        }
    }
}
