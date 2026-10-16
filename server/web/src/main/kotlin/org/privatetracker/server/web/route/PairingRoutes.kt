package org.privatetracker.server.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.strong
import kotlinx.html.unsafe
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.WebPairing
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.page.Section
import org.privatetracker.server.web.page.hx
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.page.time
import org.privatetracker.server.web.qrMatrix
import org.privatetracker.server.web.qrSvg
import org.privatetracker.server.web.security.requireSession

internal fun Route.pairingRoutes(deps: WebDependencies, assets: Assets) {
    get("/pair") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        call.respondHtml { page(ctx, "Emparejar", Section.DEVICES) { pairingStart(deps.pairing.urls(), error = null) } }
    }

    post("/pair") {
        val session = call.requireSession(deps)
        when (val created = deps.pairing.create()) {
            is Outcome.Success -> call.seeOther("/pair/${created.value.ticketId}")
            is Outcome.Failure -> {
                val error = when (created.error) {
                    is DomainError.Validation -> "El servidor no conoce ninguna dirección que los teléfonos puedan usar."
                    DomainError.DeviceKeyUnavailable -> "La clave del servidor no responde; revisa su registro."
                    else -> "No se pudo crear el código."
                }
                call.noStore()
                val ctx = call.pageContext(deps, assets, session)
                call.respondHtml(HttpStatusCode.ServiceUnavailable) { page(ctx, "Emparejar", Section.DEVICES) { pairingStart(deps.pairing.urls(), error) } }
            }
        }
    }

    get("/pair/{ticket}") {
        val session = call.requireSession(deps)
        val ticket = call.parameters["ticket"].orEmpty()
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val state = deps.pairing.state(ticket) ?: WebPairing.State.Ended
        call.respondHtml {
            page(ctx, "Emparejar", Section.DEVICES) {
                section("card pairing") {
                    h1 { +"Emparejar un teléfono" }
                    if (state is WebPairing.State.Waiting) {
                        pairingCode(ctx, state.invite)
                    }
                    pairingStatus(ticket, state)
                }
            }
        }
    }

    // Asked every 2 s by the page; the asking is also what keeps the invite alive.
    get("/pair/{ticket}/status") {
        val session = call.requireSession(deps)
        val ticket = call.parameters["ticket"].orEmpty()
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val state = deps.pairing.state(ticket) ?: WebPairing.State.Ended
        call.respondFragment {
            pairingStatus(ticket, state)
            // Once over, the QR goes too: nobody should scan a code that no longer works.
            if (state !is WebPairing.State.Waiting) {
                div {
                    id = "pair-code"
                    hx("swap-oob", "true")
                }
            }
        }
    }

    post("/pair/{ticket}/cancel") {
        call.requireSession(deps)
        deps.pairing.cancel(call.parameters["ticket"].orEmpty())
        call.seeOther("/devices")
    }
}

private fun FlowContent.pairingStart(urls: List<String>, error: String?) {
    section("card narrow") {
        h1 { +"Emparejar un teléfono" }
        ol("steps") {
            li { +"Genera un código QR aquí." }
            li { +"En el teléfono, abre PrivateTracker › Rastreo › Escanear QR y apunta la cámara al código." }
            li { +"El teléfono queda aprobado y empieza a enviar su posición." }
        }
        p("muted") { +"El código vale 10 minutos, sirve para un solo teléfono y deja de valer si sales de esta página." }
        error?.let { div("error") { attributes["role"] = "alert"; +it } }
        if (urls.isEmpty()) {
            div("notice") {
                p { +"El servidor no sabe en qué dirección lo encuentran los teléfonos." }
                p {
                    +"Arráncalo con "
                    code { +"--public-url https://<dirección>:<puerto>" }
                    +" o define "
                    code { +"PRIVATETRACKER_PUBLIC_URLS" }
                    +" (en Docker es obligatorio)."
                }
            }
        } else {
            p { +"Los teléfonos lo buscarán en: ${urls.joinToString(", ")}" }
            form(action = "/pair", method = FormMethod.post) {
                button(type = ButtonType.submit, classes = "primary") { +"Generar código QR" }
            }
        }
    }
}

private fun FlowContent.pairingCode(ctx: PageContext, invite: PairingInvite) {
    val uri = PairingUri.format(invite)
    div("pair-code") {
        id = "pair-code"
        div("qr") { unsafe { +qrSvg(qrMatrix(uri), "Código QR para emparejar un teléfono") } }
        div("pair-facts") {
            p {
                +"Huella del servidor: "
                strong { code { +(keyFingerprint(invite.serverKey) ?: "?") } }
            }
            p("muted") { +"El teléfono mostrará la misma huella al emparejarse." }
            p("muted") { +"Direcciones: ${invite.serverUrls.joinToString(", ")}" }
            p("muted") { +"Vale hasta las ${time(invite.expiresAt, ctx.zone)}." }
            label {
                +"¿Sin cámara? Abre este enlace en el teléfono:"
                input(InputType.text, name = "uri") {
                    value = uri
                    readonly = true
                    attributes["aria-label"] = "Enlace de emparejamiento"
                    attributes["data-select-on-focus"] = ""
                }
            }
        }
    }
}

private fun FlowContent.pairingStatus(ticket: String, state: WebPairing.State) {
    div("pair-status") {
        id = "pair-status"
        when (state) {
            is WebPairing.State.Waiting -> {
                hx("get", "/pair/$ticket/status")
                hx("trigger", "every 2s")
                hx("swap", "outerHTML")
                p("waiting") { +"Esperando al teléfono…" }
                form(action = "/pair/$ticket/cancel", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "secondary") { +"Cancelar" }
                }
            }
            is WebPairing.State.Paired -> {
                div("success") {
                    attributes["role"] = "status"
                    +"Emparejado: ${state.device.name}. Ya está aprobado y puede enviar su posición."
                }
                p { a(href = "/devices", classes = "button primary") { +"Ver dispositivos" } }
            }
            WebPairing.State.Expired, WebPairing.State.Ended -> {
                div("notice") {
                    attributes["role"] = "status"
                    +if (state == WebPairing.State.Expired) "El código venció sin que ningún teléfono lo usara." else "Este código ya no vale: se generó otro o se dejó de mostrar."
                }
                form(action = "/pair", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "primary") { +"Generar otro código" }
                }
            }
        }
    }
}
