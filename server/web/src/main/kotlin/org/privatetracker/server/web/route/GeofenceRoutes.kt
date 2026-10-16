package org.privatetracker.server.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.SerializationException
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeoPoint
import org.privatetracker.core.domain.model.Geofence
import org.privatetracker.core.domain.model.GeofenceEventView
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.usecase.server.GeofenceDraft
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.DraftShape
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.page.Section
import org.privatetracker.server.web.page.WebJson
import org.privatetracker.server.web.page.dateTime
import org.privatetracker.server.web.page.draftShapeJson
import org.privatetracker.server.web.page.hx
import org.privatetracker.server.web.page.jsonData
import org.privatetracker.server.web.page.mapAttributes
import org.privatetracker.server.web.page.mapDevicesJson
import org.privatetracker.server.web.page.mapGeofencesJson
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.page.relativeTime
import org.privatetracker.server.web.page.transitionLabel
import org.privatetracker.server.web.page.violationText
import org.privatetracker.server.web.security.requireSession

private const val RECENT_EVENTS = 50

internal fun Route.geofenceRoutes(deps: WebDependencies, assets: Assets) {
    get("/geofences") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val geofences = deps.observeGeofences().first().sortedBy { it.name.lowercase() }
        val names = deps.getDeviceOverviews().associate { it.device.id to it.device.name }
        val events = deps.observeGeofenceEvents(RECENT_EVENTS).first()
        call.respondHtml {
            page(ctx, "Geocercas", Section.GEOFENCES) {
                div("page-head") {
                    h1 { +"Geocercas" }
                    a(href = "/geofences/new", classes = "button primary") { +"Nueva geocerca" }
                }
                if (geofences.isEmpty()) {
                    div("card empty") {
                        p { +"Todavía no hay geocercas." }
                        p { +"Dibuja una zona en el mapa y el servidor anotará cada vez que un dispositivo entre o salga de ella." }
                    }
                } else {
                    ul("geofence-cards") {
                        geofences.forEach { geofence ->
                            li("card geofence-card") {
                                div("geofence-card-head") {
                                    span("geofence-name") { +geofence.name }
                                    a(href = "/geofences/${geofence.id.value}", classes = "button secondary small") { +"Editar" }
                                }
                                p("muted") { +shapeSummary(geofence.shape) }
                                p("muted") { +devicesSummary(geofence, names) }
                                p("muted") { +alertsSummary(geofence) }
                            }
                        }
                    }
                }
                h2 { +"Eventos recientes" }
                eventsSection(ctx, events)
            }
        }
    }

    // What htmx swaps in when an alert arrives on the live stream.
    get("/geofences/events") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val events = deps.observeGeofenceEvents(RECENT_EVENTS).first()
        call.respondFragment { eventsSection(ctx, events) }
    }

    get("/geofences/new") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val editor = Editor.empty()
        respondEditor(call, ctx, deps, editor, HttpStatusCode.OK)
    }

    get("/geofences/{id}") {
        val session = call.requireSession(deps)
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val geofence = deps.getGeofence(call.geofenceIdParameter()) ?: throw NotFoundException()
        respondEditor(call, ctx, deps, Editor.of(geofence), HttpStatusCode.OK)
    }

    post("/geofences") {
        save(deps, assets, id = null)
    }

    post("/geofences/{id}") {
        save(deps, assets, call.geofenceIdParameter())
    }

    post("/geofences/{id}/delete") {
        call.requireSession(deps)
        deps.deleteGeofence(call.geofenceIdParameter())
        call.seeOther("/geofences")
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.save(deps: WebDependencies, assets: Assets, id: GeofenceId?) {
    val session = call.requireSession(deps)
    val form = call.receiveParameters()
    val editor = Editor.from(id, form)
    val result = deps.saveGeofence(editor.draft())
    when (result) {
        is Outcome.Success -> call.seeOther("/geofences")
        is Outcome.Failure -> {
            val messages = when (val error = result.error) {
                is DomainError.Validation -> error.violations.map(::violationText).distinct()
                DomainError.GeofenceNotFound -> listOf("La geocerca ya no existe: se borró mientras la editabas.")
                else -> listOf("No se pudo guardar la geocerca.")
            }
            call.noStore()
            val ctx = call.pageContext(deps, assets, session)
            respondEditor(call, ctx, deps, editor.copy(errors = messages), HttpStatusCode.UnprocessableEntity)
        }
    }
}

/** The editor's form as filled in: what the page shows again when the server refuses it. */
private data class Editor(
    val id: GeofenceId?,
    val name: String,
    val shape: DraftShape,
    val allDevices: Boolean,
    val devices: Set<DeviceId>,
    val notifyOnEnter: Boolean,
    val notifyOnExit: Boolean,
    val errors: List<String> = emptyList(),
) {
    fun draft(): GeofenceDraft {
        val shape = when (shape.kind) {
            "polygon" -> GeofenceShape.Polygon(shape.vertices.mapNotNull { it.toPoint() })
            else -> GeofenceShape.Circle(shape.center?.toPoint() ?: NOWHERE, shape.radiusM ?: 0.0)
        }
        return GeofenceDraft(id, name, shape, if (allDevices) null else devices, notifyOnEnter, notifyOnExit)
    }

    companion object {
        /** Fails the domain's check for a center, which the form then asks for. */
        private val NOWHERE = GeoPoint(Double.NaN, Double.NaN)

        fun empty() = Editor(null, "", WebJson.decodeFromString(DraftShape.serializer(), draftShapeJson(null)), true, emptySet(), true, true)

        fun of(geofence: Geofence) = Editor(
            id = geofence.id,
            name = geofence.name,
            shape = WebJson.decodeFromString(DraftShape.serializer(), draftShapeJson(geofence.shape)),
            allDevices = geofence.deviceIds == null,
            devices = geofence.deviceIds.orEmpty(),
            notifyOnEnter = geofence.notifyOnEnter,
            notifyOnExit = geofence.notifyOnExit,
        )

        fun from(id: GeofenceId?, form: Parameters): Editor {
            val drawn = try {
                form["shape"]?.let { WebJson.decodeFromString(DraftShape.serializer(), it) }
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            val kind = if (form["kind"] == "polygon") "polygon" else "circle"
            // The radius field is what the owner typed; the map only moves the center.
            val shape = (drawn ?: DraftShape(kind)).copy(kind = kind, radiusM = form["radius"]?.replace(',', '.')?.toDoubleOrNull())
            return Editor(
                id = id,
                name = form["name"].orEmpty(),
                shape = shape.copy(vertices = shape.vertices.take(100)),
                allDevices = form["devices"] != "some",
                devices = form.getAll("device").orEmpty().mapNotNull(DeviceId::parse).toSet(),
                notifyOnEnter = form["enter"] != null,
                notifyOnExit = form["exit"] != null,
            )
        }

        private fun List<Double>.toPoint(): GeoPoint? = if (size == 2) GeoPoint(latitude = this[1], longitude = this[0]) else null
    }
}

private suspend fun respondEditor(
    call: io.ktor.server.application.ApplicationCall,
    ctx: PageContext,
    deps: WebDependencies,
    editor: Editor,
    status: HttpStatusCode,
) {
    val overviews = deps.getDeviceOverviews()
    val others = deps.observeGeofences().first().filter { it.id != editor.id }
    val action = editor.id?.let { "/geofences/${it.value}" } ?: "/geofences"
    val title = if (editor.id == null) "Nueva geocerca" else "Editar geocerca"
    call.respondHtml(status) {
        page(ctx, title, Section.GEOFENCES, maps = true, scripts = listOf(Assets.GEOFENCE_EDITOR_JS), fullScreen = true) {
            div("map-layout") {
                div("panel") {
                    p { a(href = "/geofences") { +"← Geocercas" } }
                    h1 { +title }
                    editor.errors.forEach { div("error") { attributes["role"] = "alert"; +it } }
                    form(action = action, method = FormMethod.post, classes = "stack") {
                        id = "geofence-form"
                        // Which fields show before the script runs; the script keeps them current.
                        attributes["data-kind"] = editor.shape.kind
                        attributes["data-devices"] = if (editor.allDevices) "all" else "some"
                        label {
                            +"Nombre"
                            input(InputType.text, name = "name") {
                                value = editor.name
                                required = true
                                maxLength = Geofence.NAME_MAX_LENGTH.toString()
                                attributes["placeholder"] = "Casa, Escuela, Oficina…"
                            }
                        }
                        fieldSet {
                            legend { +"Forma" }
                            label("radio") {
                                input(InputType.radio, name = "kind") { value = "circle"; checked = editor.shape.kind != "polygon" }
                                +" Círculo"
                            }
                            label("radio") {
                                input(InputType.radio, name = "kind") { value = "polygon"; checked = editor.shape.kind == "polygon" }
                                +" Polígono"
                            }
                        }
                        div("circle-only") {
                            label {
                                +"Radio en metros (50 a 50 000)"
                                input(InputType.number, name = "radius") {
                                    id = "radius"
                                    min = Geofence.MIN_RADIUS_M.toInt().toString()
                                    max = Geofence.MAX_RADIUS_M.toInt().toString()
                                    step = "10"
                                    value = editor.shape.radiusM?.let { "%.0f".format(it) } ?: "200"
                                }
                            }
                            p("hint") { +"Toca el mapa para poner el centro." }
                        }
                        div("polygon-only") {
                            p("hint") { +"Toca el mapa para poner cada vértice, en orden alrededor de la zona." }
                            div("actions") {
                                button(type = ButtonType.button, classes = "secondary small") { id = "undo-vertex"; +"Quitar el último" }
                                button(type = ButtonType.button, classes = "secondary small") { id = "clear-vertices"; +"Empezar de nuevo" }
                            }
                        }
                        hiddenInput(name = "shape") {
                            id = "shape"
                            value = WebJson.encodeToString(DraftShape.serializer(), editor.shape)
                        }
                        fieldSet {
                            legend { +"Dispositivos" }
                            label("radio") {
                                input(InputType.radio, name = "devices") { value = "all"; checked = editor.allDevices }
                                +" Todos, también los que se emparejen después"
                            }
                            label("radio") {
                                input(InputType.radio, name = "devices") { value = "some"; checked = !editor.allDevices }
                                +" Solo estos:"
                            }
                            div("device-choices") {
                                overviews.forEach { overview ->
                                    label("check") {
                                        input(InputType.checkBox, name = "device") {
                                            value = overview.device.id.value
                                            checked = overview.device.id in editor.devices
                                        }
                                        +" ${overview.device.name}"
                                    }
                                }
                            }
                        }
                        fieldSet {
                            legend { +"Avisos" }
                            label("check") {
                                input(InputType.checkBox, name = "enter") { checked = editor.notifyOnEnter }
                                +" Al entrar"
                            }
                            label("check") {
                                input(InputType.checkBox, name = "exit") { checked = editor.notifyOnExit }
                                +" Al salir"
                            }
                            p("hint") { +"Sin avisos, los cruces solo se anotan en Eventos recientes." }
                        }
                        button(type = ButtonType.submit, classes = "primary") { +"Guardar" }
                    }
                    editor.id?.let { id ->
                        confirm("Borrar geocerca…", "Se borran la geocerca y sus eventos.") {
                            form(action = "/geofences/${id.value}/delete", method = FormMethod.post, classes = "inline") {
                                button(type = ButtonType.submit, classes = "danger") { +"Borrar definitivamente" }
                            }
                        }
                    }
                }
                div("map") {
                    id = "map"
                    mapAttributes(ctx, deps.tiles)
                    attributes["role"] = "region"
                    attributes["aria-label"] = "Mapa para dibujar la geocerca"
                }
            }
            jsonData("devices-data", mapDevicesJson(overviews))
            jsonData("geofences-data", mapGeofencesJson(others))
        }
    }
}

private fun FlowContent.eventsSection(ctx: PageContext, events: List<GeofenceEventView>) {
    section {
        id = "events"
        hx("get", "/geofences/events")
        hx("trigger", "pt-alert from:body")
        hx("swap", "outerHTML")
        if (events.isEmpty()) {
            p("muted") { +"Todavía no hay entradas ni salidas registradas." }
            return@section
        }
        div("table-wrap") {
            table("events") {
                thead {
                    tr {
                        th { +"Cuándo" }
                        th { +"Dispositivo" }
                        th { +"Geocerca" }
                        th { +"Qué" }
                        th { +"Aviso" }
                    }
                }
                tbody {
                    events.forEach { view ->
                        tr {
                            td {
                                span { attributes["title"] = dateTime(view.event.occurredAt, ctx.zone); +relativeTime(view.event.occurredAt, ctx.now) }
                            }
                            td { +view.deviceName }
                            td { +view.geofenceName }
                            td { +transitionLabel(view.event.transition) }
                            td {
                                if (view.event.alerted) {
                                    +"Sí"
                                } else {
                                    span("muted") {
                                        attributes["title"] = "No se avisó: la geocerca no lo pide, llegó tarde o no había ninguna página abierta ni webhook."
                                        +"No"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shapeSummary(shape: GeofenceShape): String = when (shape) {
    is GeofenceShape.Circle -> "Círculo de ${"%,.0f".format(java.util.Locale.forLanguageTag("es-MX"), shape.radiusM)} m de radio"
    is GeofenceShape.Polygon -> "Polígono de ${shape.vertices.size} vértices"
}

private fun devicesSummary(geofence: Geofence, names: Map<DeviceId, String>): String {
    val ids = geofence.deviceIds ?: return "Todos los dispositivos"
    val known = ids.mapNotNull(names::get).sorted()
    return if (known.isEmpty()) "Ningún dispositivo de los que hay ahora" else known.joinToString(", ")
}

private fun alertsSummary(geofence: Geofence): String = when {
    geofence.notifyOnEnter && geofence.notifyOnExit -> "Avisa al entrar y al salir"
    geofence.notifyOnEnter -> "Avisa al entrar"
    geofence.notifyOnExit -> "Avisa al salir"
    else -> "Sin avisos: solo anota los cruces"
}
