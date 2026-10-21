package org.privatetracker.server.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
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
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.strong
import kotlinx.html.unsafe
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.Peer
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.WebInvites
import org.privatetracker.server.web.WebPeers
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.page.Section
import org.privatetracker.server.web.page.dateTime
import org.privatetracker.server.web.page.hx
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.page.peerStateLabel
import org.privatetracker.server.web.page.time
import org.privatetracker.server.web.qrMatrix
import org.privatetracker.server.web.qrSvg
import org.privatetracker.server.web.security.requireSession

/** Paired servers (1.0): the list, an invite for another server, joining another's, and leaving one. */
internal fun Route.peerRoutes(deps: WebDependencies, assets: Assets) {
    val peers = deps.peers ?: return

    get("/peers") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val list = peers.list()
        val message = when (call.parameters["done"]) {
            "joined" -> "Emparejado. El primer intercambio empieza ya; luego, uno por minuto."
            "removed" -> "Ya no se comparte nada con ese servidor. Quita también el emparejamiento allá."
            else -> null
        }
        call.respondHtml { page(ctx, "Servidores emparejados", Section.SERVER) { peersPage(ctx, list, message, error = null) } }
    }

    post("/peers/join") {
        val session = call.requireSession(deps)
        val link = call.receiveParameters()["link"].orEmpty()
        val invite = PairingUri.parse(link, PairingUri.PEER_HOST)
        val error = if (invite == null) {
            "Eso no es una invitación de servidor: empieza con privatetracker://peer?"
        } else {
            when (val joined = peers.join(invite)) {
                is Outcome.Success -> null
                is Outcome.Failure -> joinError(joined.error)
            }
        }
        if (error == null) {
            call.seeOther("/peers?done=joined")
        } else {
            call.noStore()
            val ctx = call.pageContext(deps, assets, session)
            val list = peers.list()
            call.respondHtml(HttpStatusCode.UnprocessableEntity) { page(ctx, "Servidores emparejados", Section.SERVER) { peersPage(ctx, list, message = null, error) } }
        }
    }

    post("/peers/{label}/remove") {
        call.requireSession(deps)
        val label = call.parameters["label"].orEmpty()
        // One another tab already removed is simply gone.
        peers.list().firstOrNull { it.label == label }?.let { peers.remove(it.publicKey) }
        call.seeOther("/peers?done=removed")
    }

    post("/peers/invite") {
        val session = call.requireSession(deps)
        when (val created = peers.invites.create()) {
            is Outcome.Success -> call.seeOther("/peers/invite/${created.value.ticketId}")
            is Outcome.Failure -> {
                val error = when (created.error) {
                    is DomainError.Validation -> "El servidor no conoce ninguna dirección en la que otro servidor lo encuentre."
                    else -> "La clave del servidor no responde; revisa su registro."
                }
                call.noStore()
                val ctx = call.pageContext(deps, assets, session)
                val list = peers.list()
                call.respondHtml(HttpStatusCode.ServiceUnavailable) { page(ctx, "Servidores emparejados", Section.SERVER) { peersPage(ctx, list, message = null, error) } }
            }
        }
    }

    get("/peers/invite/{ticket}") {
        val session = call.requireSession(deps)
        val ticket = call.parameters["ticket"].orEmpty()
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val state = peers.invites.state(ticket) ?: WebInvites.State.Ended
        call.respondHtml {
            page(ctx, "Invitar a un servidor", Section.SERVER) {
                section("card pairing") {
                    h1 { +"Invitar a otro servidor" }
                    if (state is WebInvites.State.Waiting) inviteCode(ctx, state.invite)
                    inviteStatus(ticket, state)
                }
            }
        }
    }

    // Asked every 2 s by the page; the asking is also what keeps the invite alive.
    get("/peers/invite/{ticket}/status") {
        call.requireSession(deps)
        val ticket = call.parameters["ticket"].orEmpty()
        call.noStore()
        val state = peers.invites.state(ticket) ?: WebInvites.State.Ended
        call.respondFragment {
            inviteStatus(ticket, state)
            if (state !is WebInvites.State.Waiting) {
                div {
                    id = "pair-code"
                    hx("swap-oob", "true")
                }
            }
        }
    }

    post("/peers/invite/{ticket}/cancel") {
        call.requireSession(deps)
        peers.invites.cancel(call.parameters["ticket"].orEmpty())
        call.seeOther("/peers")
    }
}

private fun joinError(error: DomainError): String = when (error) {
    DomainError.PairingInvalid -> "La invitación venció, ya la usó otro servidor o es de este mismo. Pide una nueva."
    DomainError.ServerIdentityMismatch -> "En esa dirección responde otro servidor, no el de la invitación."
    is DomainError.Network -> "No se pudo llegar al otro servidor en ninguna de sus direcciones."
    DomainError.DeviceKeyUnavailable -> "La clave de este servidor no responde; revisa su registro."
    else -> "El otro servidor no aceptó la invitación (${error.code})."
}

private fun FlowContent.peersPage(ctx: PageContext, peers: List<Peer>, message: String?, error: String?) {
    h1 { +"Servidores emparejados" }
    p("muted") {
        +"Dos servidores emparejados comparten en ambos sentidos sus dispositivos y posiciones, más o menos cada minuto: "
        +"aprobar, renombrar o borrar un dispositivo en uno vale en los dos. Geocercas, cuentas y claves siguen siendo de cada uno."
    }
    message?.let { div("success") { attributes["role"] = "status"; +it } }
    error?.let { div("error") { attributes["role"] = "alert"; +it } }
    div("cards") {
        peers.forEach { peer ->
            card(peer.name) {
                facts(
                    "Huella" to peer.fingerprint,
                    "Estado" to peerStateLabel(peer, ctx.now),
                    "Emparejado" to dateTime(peer.pairedAt, ctx.zone),
                    "Dirección" to (peer.addresses.firstOrNull() ?: "ninguna"),
                )
                confirm("Dejar de compartir…", "Este servidor deja de enviarle y de recibir de «${peer.name}». Lo que ya llegó se queda.") {
                    form(action = "/peers/${peer.label}/remove", method = FormMethod.post, classes = "inline") {
                        button(type = ButtonType.submit, classes = "danger") { +"Dejar de compartir" }
                    }
                }
            }
        }
        card("Invitar a otro servidor") {
            p { +"Genera una invitación de un solo uso: un código QR y un enlace que valen 10 minutos." }
            form(action = "/peers/invite", method = FormMethod.post) {
                button(type = ButtonType.submit, classes = "primary") { +"Generar invitación" }
            }
        }
        card("Unirme a otro servidor") {
            form(action = "/peers/join", method = FormMethod.post) {
                label {
                    +"Pega el enlace de la invitación del otro servidor:"
                    input(InputType.text, name = "link") {
                        required = true
                        placeholder = "privatetracker://peer?..."
                        attributes["autocomplete"] = "off"
                    }
                }
                button(type = ButtonType.submit, classes = "primary") { +"Unirme" }
            }
        }
    }
}

private fun FlowContent.inviteCode(ctx: PageContext, invite: PairingInvite) {
    val uri = PairingUri.format(invite, PairingUri.PEER_HOST)
    div("pair-code") {
        id = "pair-code"
        div("qr") { unsafe { +qrSvg(qrMatrix(uri), "Código QR para emparejar otro servidor") } }
        div("pair-facts") {
            p {
                +"Huella de este servidor: "
                strong { code { +(keyFingerprint(invite.serverKey) ?: "?") } }
            }
            p("muted") { +"El otro servidor mostrará la misma huella." }
            p("muted") { +"En un teléfono servidor: Servidor › Servidores emparejados › Unirme, y escanea el código." }
            p("muted") { +"Vale hasta las ${time(invite.expiresAt, ctx.zone)}." }
            label {
                +"En otro servidor Linux, pega este enlace en «Unirme a otro servidor» o cuando lo pida privatetracker-server peers join:"
                input(InputType.text, name = "uri") {
                    value = uri
                    readonly = true
                    attributes["aria-label"] = "Enlace de la invitación"
                    attributes["data-select-on-focus"] = ""
                }
            }
        }
    }
}

private fun FlowContent.inviteStatus(ticket: String, state: WebInvites.State<Peer>) {
    div("pair-status") {
        id = "pair-status"
        when (state) {
            is WebInvites.State.Waiting -> {
                hx("get", "/peers/invite/$ticket/status")
                hx("trigger", "every 2s")
                hx("swap", "outerHTML")
                p("waiting") { +"Esperando al otro servidor…" }
                form(action = "/peers/invite/$ticket/cancel", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "secondary") { +"Cancelar" }
                }
            }
            is WebInvites.State.Used -> {
                div("success") {
                    attributes["role"] = "status"
                    +"Emparejado con ${state.by.name} (huella ${state.by.fingerprint}). Compara la huella en ese servidor."
                }
                p { a(href = "/peers", classes = "button primary") { +"Ver servidores" } }
            }
            WebInvites.State.Expired, WebInvites.State.Ended -> {
                div("notice") {
                    attributes["role"] = "status"
                    +if (state == WebInvites.State.Expired) "La invitación venció sin que otro servidor la usara." else "Esta invitación ya no vale: se generó otra o se dejó de mostrar."
                }
                form(action = "/peers/invite", method = FormMethod.post) {
                    button(type = ButtonType.submit, classes = "primary") { +"Generar otra" }
                }
            }
        }
    }
}
