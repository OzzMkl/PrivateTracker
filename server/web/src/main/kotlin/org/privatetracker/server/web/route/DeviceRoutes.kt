package org.privatetracker.server.web.route

import io.ktor.server.html.respondHtml
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.ul
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceOverview
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.page.Section
import org.privatetracker.server.web.page.approvalLabel
import org.privatetracker.server.web.page.dateTime
import org.privatetracker.server.web.page.hx
import org.privatetracker.server.web.page.jsonData
import org.privatetracker.server.web.page.mapAttributes
import org.privatetracker.server.web.page.mapDevicesJson
import org.privatetracker.server.web.page.mapGeofencesJson
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.page.platformLabel
import org.privatetracker.server.web.page.relativeTime
import org.privatetracker.server.web.page.statusLabel
import org.privatetracker.server.web.security.isHtmx
import org.privatetracker.server.web.security.requireSession

/** The main map: every approved device where it last was, live, with the geofences. */
internal fun Route.mapRoutes(deps: WebDependencies, assets: Assets) {
    get("/") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val overviews = deps.getDeviceOverviews()
        val geofences = deps.observeGeofences().first()
        val pending = overviews.count { it.device.awaitsApproval }
        call.respondHtml {
            page(ctx, "Mapa", Section.MAP, maps = true, scripts = listOf(Assets.LIVE_MAP_JS), fullScreen = true) {
                div("map-layout") {
                    aside("panel") {
                        if (pending > 0) {
                            a(href = "/devices", classes = "notice link-notice") {
                                +"${if (pending == 1) "Un dispositivo espera" else "$pending dispositivos esperan"} tu aprobación"
                            }
                        }
                        div("panel-head") {
                            h2 { +"Dispositivos" }
                            if (geofences.isNotEmpty()) {
                                label("toggle") {
                                    input(InputType.checkBox) {
                                        id = "show-geofences"
                                        checked = true
                                    }
                                    +" Geocercas"
                                }
                            }
                        }
                        ul("device-list") {
                            id = "device-list"
                            val shown = overviews.filter { it.device.approval == DeviceApproval.APPROVED && it.lastLocation != null }
                            if (shown.isEmpty()) {
                                li("empty") { +"Ningún dispositivo ha enviado su posición todavía." }
                            }
                            shown.forEach { deviceListItem(ctx, it) }
                        }
                        p("muted small") { id = "updated-at" }
                    }
                    div("map") {
                        id = "map"
                        mapAttributes(ctx, deps.tiles)
                        attributes["role"] = "region"
                        attributes["aria-label"] = "Mapa de dispositivos"
                    }
                }
                jsonData("devices-data", mapDevicesJson(overviews))
                jsonData("geofences-data", mapGeofencesJson(geofences))
            }
        }
    }
}

/** One entry of the map's list as the server draws it; live-map.js draws the same from the live stream. */
private fun UL.deviceListItem(ctx: PageContext, overview: DeviceOverview) {
    val device = overview.device
    li("device-item") {
        attributes["data-id"] = device.id.value
        button(type = ButtonType.button, classes = "device-focus") {
            span("dot ${overview.status.name.lowercase()}") { attributes["aria-hidden"] = "true" }
            span("device-name") { +device.name }
            span("device-seen") { +relativeTime(overview.lastLocation?.recordedAt, ctx.now) }
        }
        a(href = "/devices/${device.id.value}/history", classes = "small") { +"Historial" }
    }
}

internal fun Route.deviceRoutes(deps: WebDependencies, assets: Assets) {
    get("/devices") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val overviews = deps.getDeviceOverviews()
        call.respondHtml {
            page(ctx, "Dispositivos", Section.DEVICES) {
                div("page-head") {
                    h1 { +"Dispositivos" }
                    a(href = "/pair", classes = "button primary") { +"Emparejar un teléfono" }
                }
                devicesSection(ctx, overviews)
            }
        }
    }

    // What htmx swaps in when the live stream says the devices changed.
    get("/devices/section") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val overviews = deps.getDeviceOverviews()
        call.respondFragment { devicesSection(ctx, overviews) }
    }

    post("/devices/{id}/{action}") {
        val session = call.requireSession(deps)
        val id = call.deviceIdParameter()
        when (call.parameters["action"]) {
            "approve" -> deps.setDeviceApproval(id, DeviceApproval.APPROVED)
            "reject" -> deps.setDeviceApproval(id, DeviceApproval.REJECTED)
            "remove" -> deps.removeDevice(id)
            else -> throw io.ktor.server.plugins.NotFoundException()
        }
        // A device another tab already removed is simply gone from the list.
        if (call.isHtmx) {
            val ctx = call.pageContext(deps, assets, session)
            val overviews = deps.getDeviceOverviews()
            call.noStore()
            call.respondFragment { devicesSection(ctx, overviews) }
        } else {
            call.seeOther("/devices")
        }
    }
}

private fun FlowContent.devicesSection(ctx: PageContext, overviews: List<DeviceOverview>) {
    section {
        id = "devices"
        hx("get", "/devices/section")
        hx("trigger", "pt-refresh")
        hx("swap", "outerHTML")
        if (overviews.isEmpty()) {
            div("card empty") {
                p { +"Todavía no hay dispositivos." }
                p { +"Empareja el primero con el botón de arriba: el teléfono escanea un código QR y queda aprobado." }
            }
            return@section
        }
        val (waiting, others) = overviews.partition { it.device.awaitsApproval }
        if (waiting.isNotEmpty()) {
            h2 { +"Esperan tu aprobación" }
            p("muted") { +"Aprueba solo si la huella coincide con la que muestra el teléfono en Rastreo." }
            ul("device-cards") { waiting.forEach { deviceCard(ctx, it) } }
            h2 { +"Todos los dispositivos" }
        }
        ul("device-cards") { others.forEach { deviceCard(ctx, it) } }
    }
}

private fun UL.deviceCard(ctx: PageContext, overview: DeviceOverview) {
    val device = overview.device
    val id = device.id.value
    li("device-card") {
        div("device-card-head") {
            span("dot ${overview.status.name.lowercase()}") { attributes["aria-hidden"] = "true" }
            span("device-name") { +device.name }
            span("badge ${device.approval.name.lowercase()}") { +approvalLabel(device.approval) }
        }
        div("device-facts") {
            span {
                +statusLabel(overview.status)
                +" · "
                span {
                    device.lastSeenAt?.let { attributes["title"] = dateTime(it, ctx.zone) }
                    +"visto ${relativeTime(device.lastSeenAt, ctx.now)}"
                }
            }
            small("muted") { +listOfNotNull(platformLabel(device.platform), device.appVersion?.let { "app $it" }).joinToString(" · ") }
            span("muted") {
                +"Huella "
                code { +(device.publicKey?.let(::keyFingerprint) ?: "sin clave") }
            }
        }
        if (device.publicKey == null) {
            p("muted small") { +"Registrado por una versión anterior a 0.2: se pedirá aprobación cuando vuelva a registrarse con su clave." }
        }
        div("actions") {
            when (device.approval) {
                DeviceApproval.PENDING -> if (device.awaitsApproval) {
                    actionButton(id, "approve", "Aprobar", "primary")
                    actionButton(id, "reject", "Rechazar", "secondary")
                }
                DeviceApproval.APPROVED -> {
                    a(href = "/devices/$id/history", classes = "button secondary") { +"Historial" }
                    confirm("Revocar acceso…", "Deja de aceptar sus posiciones y conserva su historial.") {
                        actionButton(id, "reject", "Revocar acceso", "danger")
                    }
                }
                DeviceApproval.REJECTED -> actionButton(id, "approve", "Volver a aprobar", "secondary")
            }
            confirm("Borrar…", "Se borran el dispositivo y todo su historial. Podrá pedir entrar de nuevo.") {
                actionButton(id, "remove", "Borrar definitivamente", "danger")
            }
        }
    }
}

/** A button that works with or without htmx: a plain form post, which htmx turns into a swap of the list. */
private fun FlowContent.actionButton(deviceId: String, action: String, label: String, style: String) {
    form(action = "/devices/$deviceId/$action", method = FormMethod.post, classes = "inline") {
        hx("post", "/devices/$deviceId/$action")
        hx("target", "#devices")
        hx("swap", "outerHTML")
        button(type = ButtonType.submit, classes = style) { +label }
    }
}

/** A second step without a script: the button that does it only shows once the first is opened. */
internal fun FlowContent.confirm(label: String, explanation: String, action: FlowContent.() -> Unit) {
    details("confirm") {
        summary("button secondary") { +label }
        div("confirm-body") {
            p { +explanation }
            action()
        }
    }
}
