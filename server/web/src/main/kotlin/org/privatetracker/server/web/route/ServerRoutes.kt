package org.privatetracker.server.web.route

import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.RelayState
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.Section
import org.privatetracker.server.web.page.date
import org.privatetracker.server.web.page.dateTime
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.page.peerStateLabel
import org.privatetracker.server.web.page.relativeTime
import org.privatetracker.server.web.page.relayStateLabel
import org.privatetracker.server.web.security.requireSession
import java.time.Duration

internal fun Route.serverRoutes(deps: WebDependencies, assets: Assets) {
    get("/server") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val host = deps.hostStatus()
        val config = deps.serverConfig.get()
        val fingerprint = deps.getServerKeyFingerprint()
        val key = try {
            deps.encryptionKeys.current().key
        } catch (e: DeviceKeyException) {
            null
        }
        val overviews = deps.getDeviceOverviews()
        val accounts = deps.listAccounts()
        val peers = deps.peers?.list()
        val message = when (call.parameters["done"]) {
            "rotated" -> "Clave de cifrado nueva. Las anteriores se borraron; los Trackers piden la nueva solos."
            "rotate-failed" -> "No se pudo rotar la clave: el archivo de claves no responde."
            else -> null
        }
        call.respondHtml {
            page(ctx, "Servidor", Section.SERVER) {
                h1 { +"Servidor" }
                message?.let { div(if (it.startsWith("No")) "error" else "success") { attributes["role"] = "status"; +it } }
                div("cards") {
                    card("Estado") {
                        facts(
                            "Nombre" to config.serverName,
                            "Versión" to deps.serverVersion,
                            "En marcha desde" to "${dateTime(host.startedAt, ctx.zone)} (${uptime(Duration.between(host.startedAt, ctx.now))})",
                            "Peticiones de Trackers" to "%,d".format(host.trackerRequests),
                            "Dispositivos" to "${overviews.size} (${overviews.count { it.status.name == "ONLINE" }} en línea, ${overviews.count { it.device.awaitsApproval }} esperando aprobación)",
                            "Conservación" to "${config.retentionDays} días",
                        )
                    }
                    card("Conexión de los Trackers") {
                        facts(
                            "Puerto HTTPS" to host.trackerPort.toString(),
                            "Direcciones" to host.inviteUrls.ifEmpty { listOf("ninguna; define PRIVATETRACKER_PUBLIC_URLS") }.joinToString(", "),
                            "Nuevos sin QR" to if (config.acceptNewDevices) "Pueden pedir entrar (quedan pendientes)" else "Solo entran los emparejados con QR",
                        )
                        dl("facts stacked") {
                            dt { +"Huella del servidor" }
                            dd { code("fingerprint") { +(fingerprint ?: "la clave no responde") } }
                        }
                    }
                    card("App web") {
                        facts(
                            "Puerto HTTPS" to host.webPort.toString(),
                            "Certificado" to if (host.certificate.fromFile) "Propio (archivo PEM)" else "Autofirmado con la clave del servidor",
                            "Emitido para" to host.certificate.subject,
                            "Vence" to (host.certificate.notAfter?.let { date(it, ctx.zone) } ?: "nunca"),
                        )
                        dl("facts stacked") {
                            dt { +"SHA-256 del certificado" }
                            // Four lines of eight bytes, to read against the browser's certificate details.
                            dd {
                                code("fingerprint") {
                                    host.certificate.sha256.split(':').chunked(8).forEach { span("line") { +it.joinToString(":") } }
                                }
                            }
                        }
                        if (!host.certificate.fromFile) {
                            p("muted small") { +"El navegador avisa porque nadie más firmó este certificado. Compara esta huella con la que muestra el navegador en los detalles del certificado." }
                        }
                    }
                    host.relay?.let { relay ->
                        card("Relay") {
                            facts("Relay" to relay.address, "Estado" to relayStateLabel(relay.state))
                            val connected = relay.state as? RelayState.Connected
                            connected?.let {
                                facts("Trackers fuera de casa" to it.trackerUrl)
                                it.webUrl?.let { url -> facts("App web fuera de casa" to url) }
                            }
                            p("muted small") {
                                +"El relay pasa las conexiones de los Trackers sin abrirlas: fijaron la clave de este servidor, así que no puede leer ni falsificar lo que envían. Los Trackers ya emparejados aprenden su dirección solos en su siguiente comprobación diaria."
                            }
                            if (connected?.webUrl != null) {
                                p("muted small") {
                                    +"La app web por el relay no tiene esa protección: el navegador no fija ninguna clave, y quien controla el dominio del relay puede conseguir un certificado para ese nombre y hacerse pasar por esta página. Úsala solo con un relay tuyo."
                                }
                            }
                        }
                    }
                    card("Cifrado de extremo a extremo") {
                        if (key == null) {
                            p("error") { +"La clave de cifrado no está disponible: los Trackers no enviarán nada hasta que vuelva." }
                        } else {
                            facts(
                                "Clave actual" to key.id,
                                "Se usa hasta" to dateTime(key.useUntil, ctx.zone),
                            )
                        }
                        p("muted small") { +"Cambia sola cada 7 días. Rotarla ahora borra también las anteriores: solo hace falta si temes que alguien la copió." }
                        confirm("Rotar clave ahora…", "Los Trackers piden la clave nueva en su siguiente envío; no hay que volver a emparejarlos.") {
                            form(action = "/server/rotate-key", method = FormMethod.post, classes = "inline") {
                                button(type = ButtonType.submit, classes = "danger") { +"Rotar y borrar las anteriores" }
                            }
                        }
                    }
                    card("Avisos de geocercas") {
                        facts("Navegadores con la app abierta" to deps.alerts.listenerCount.toString())
                        val webhook = host.webhook
                        if (webhook == null) {
                            p("muted small") { +"Sin webhook: con todas las páginas cerradas, los cruces se anotan sin avisar. Configura PRIVATETRACKER_ALERT_WEBHOOK para recibirlos en ntfy, Gotify o Home Assistant." }
                        } else {
                            facts(
                                "Webhook" to webhook.target,
                                "Último envío" to (webhook.lastSentAt?.let { "${dateTime(it, ctx.zone)} (${relativeTime(it, ctx.now)})" } ?: "ninguno todavía"),
                            )
                            webhook.lastError?.let { p("error small") { +"Último error: $it" } }
                        }
                    }
                    peers?.let {
                        card("Servidores emparejados") {
                            if (it.isEmpty()) {
                                p("muted small") { +"Ninguno. Dos servidores emparejados comparten sus dispositivos y posiciones cada minuto." }
                            } else {
                                ul("plain") { it.forEach { peer -> li { +"${peer.name} · ${peerStateLabel(peer, ctx.now)}" } } }
                            }
                            p { a(href = "/peers", classes = "button secondary") { +"Administrar" } }
                        }
                    }
                    card("Cuentas de la app web") {
                        ul("plain") {
                            accounts.forEach { li { +"${it.name} · creada el ${date(it.createdAt, ctx.zone)}" } }
                        }
                        p("muted small") {
                            +"Se crean y se borran en el equipo del servidor: "
                            code { +"privatetracker-server users add|passwd|remove <nombre>" }
                        }
                    }
                }
            }
        }
    }

    post("/server/rotate-key") {
        call.requireSession(deps)
        val done = if (deps.rotateEncryptionKey() is Outcome.Success) "rotated" else "rotate-failed"
        call.seeOther("/server?done=$done")
    }
}

internal fun FlowContent.card(title: String, content: FlowContent.() -> Unit) {
    section("card") {
        h2 { +title }
        content()
    }
}

internal fun FlowContent.facts(vararg rows: Pair<String, String>) {
    dl("facts") {
        rows.forEach { (term, value) ->
            dt { +term }
            dd { +value }
        }
    }
}

private fun uptime(elapsed: Duration): String = when {
    elapsed.toDays() > 0 -> "${elapsed.toDays()} d ${elapsed.toHoursPart()} h"
    elapsed.toHours() > 0 -> "${elapsed.toHours()} h ${elapsed.toMinutesPart()} min"
    else -> "${elapsed.toMinutes()} min"
}
