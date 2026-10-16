package org.privatetracker.server.web.route

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.domain.export.HistoryFormat
import org.privatetracker.core.domain.model.HistoryPeriod
import org.privatetracker.core.domain.model.TimeRange
import org.privatetracker.core.domain.model.dayRange
import org.privatetracker.core.domain.usecase.server.historyFileName
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.Section
import org.privatetracker.server.web.page.count
import org.privatetracker.server.web.page.dateTime
import org.privatetracker.server.web.page.distance
import org.privatetracker.server.web.page.jsonData
import org.privatetracker.server.web.page.mapAttributes
import org.privatetracker.server.web.page.mapTrackJson
import org.privatetracker.server.web.page.page
import org.privatetracker.server.web.security.requireSession
import org.privatetracker.server.web.security.zone
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The quick ranges of the history page, as the phone offers them. */
private enum class Period(val key: String, val label: String, val domain: HistoryPeriod) {
    TODAY("today", "Hoy", HistoryPeriod.TODAY),
    YESTERDAY("yesterday", "Ayer", HistoryPeriod.YESTERDAY),
    WEEK("7d", "7 días", HistoryPeriod.LAST_7_DAYS),
    MONTH("30d", "30 días", HistoryPeriod.LAST_30_DAYS),
}

/** Drawing tolerance, as on the phone: no point moves more than this off the line drawn. */
private const val DRAW_TOLERANCE_M = 10.0

internal fun Route.historyRoutes(deps: WebDependencies, assets: Assets) {
    get("/devices/{id}/history") {
        val session = call.requireSession(deps)
        val deviceId = call.deviceIdParameter()
        call.noStore()
        val ctx = call.pageContext(deps, assets, session)
        val device = deps.getDeviceOverviews().firstOrNull { it.device.id == deviceId }?.device ?: throw NotFoundException()
        val zone = call.zone()
        val firstDay = call.parameters["from"]?.let(::parseDay)
        val lastDay = call.parameters["to"]?.let(::parseDay)
        val period = Period.entries.firstOrNull { it.key == call.parameters["period"] } ?: Period.TODAY
        val custom = firstDay != null && lastDay != null
        val range = if (custom) dayRange(minOf(firstDay, lastDay), maxOf(firstDay, lastDay), zone) else period.domain.range(ctx.now, zone)
        val track = when (val found = deps.getDeviceTrack(deviceId, range)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> throw NotFoundException()
        }
        val drawn = track.simplified(DRAW_TOLERANCE_M)
        // Rolling ranges reach a few minutes past now; the last day shown is today's, even at 23:58.
        val lastShownDay = minOf(range.to, ctx.now.plusMillis(1)).minusMillis(1).atZone(zone).toLocalDate()
        val exportQuery = "start=${range.from.toEpochMilli()}&end=${range.to.toEpochMilli()}"

        call.respondHtml {
            page(ctx, "Historial de ${device.name}", Section.DEVICES, maps = true, scripts = listOf(Assets.HISTORY_JS), fullScreen = true) {
                div("map-layout") {
                    div("panel") {
                        p { a(href = "/devices") { +"← Dispositivos" } }
                        h1 { +device.name }
                        nav("chips") {
                            attributes["aria-label"] = "Rango"
                            Period.entries.forEach { option ->
                                a(href = "/devices/${deviceId.value}/history?period=${option.key}", classes = "chip") {
                                    if (!custom && option == period) attributes["aria-current"] = "true"
                                    +option.label
                                }
                            }
                        }
                        form(action = "/devices/${deviceId.value}/history", method = FormMethod.get, classes = "range-form") {
                            label {
                                +"Desde"
                                input(InputType.date, name = "from") { value = (firstDay ?: range.from.atZone(zone).toLocalDate()).toString(); required = true }
                            }
                            label {
                                +"Hasta"
                                input(InputType.date, name = "to") { value = (lastDay ?: lastShownDay).toString(); required = true }
                            }
                            button(classes = "secondary") { +"Ver" }
                        }
                        div("facts") {
                            if (track.isEmpty) {
                                p { +"Sin posiciones en este rango." }
                            } else {
                                p {
                                    span("big") { +distance(track.distanceM) }
                                    +" en ${count(track.pointCount, "posición", "posiciones")}"
                                }
                                p("muted") { +"De ${dateTime(track.start!!, zone)} a ${dateTime(track.end!!, zone)}" }
                                if (track.segments.size > 1) {
                                    p("muted small") { +"El recorrido tiene ${track.segments.size} tramos: entre ellos el teléfono no envió nada durante un buen rato." }
                                }
                            }
                        }
                        div("actions") {
                            HistoryFormat.entries.forEach { format ->
                                a(href = "/devices/${deviceId.value}/history.${format.extension}?$exportQuery", classes = "button secondary") {
                                    attributes["download"] = ""
                                    +"Descargar ${format.name}"
                                }
                            }
                        }
                    }
                    div("map") {
                        id = "map"
                        mapAttributes(ctx, deps.tiles)
                        attributes["role"] = "region"
                        attributes["aria-label"] = "Recorrido en el mapa"
                    }
                }
                jsonData("track-data", mapTrackJson(drawn))
            }
        }
    }

    get("/devices/{id}/history.{format}") {
        call.requireSession(deps)
        val id = call.deviceIdParameter()
        val format = HistoryFormat.entries.firstOrNull { it.extension == call.parameters["format"] } ?: throw NotFoundException()
        val from = call.parameters["start"]?.toLongOrNull()
        val to = call.parameters["end"]?.toLongOrNull()
        if (from == null || to == null || to < from) throw BadRequestException("start and end are required")
        val range = TimeRange(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
        val device = deps.getDeviceOverviews().firstOrNull { it.device.id == id }?.device ?: throw NotFoundException()
        // Named after the days it covers up to now: a rolling range ends a few minutes ahead.
        val now = deps.clock.now()
        val named = TimeRange(range.from, maxOf(range.from, minOf(range.to, now.plusMillis(1))))
        val name = historyFileName(device.name, named, format, call.zone())
        call.noStore()
        call.response.header(HttpHeaders.ContentDisposition, attachment(name))
        call.respondTextWriter(ContentType.parse(format.mimeType).withCharset(Charsets.UTF_8)) {
            deps.exportDeviceHistory(id, range, format, this)
        }
    }
}

private fun parseDay(raw: String): LocalDate? = try {
    LocalDate.parse(raw)
} catch (e: DateTimeParseException) {
    null
}

/** RFC 6266: an ASCII name for old clients, and the real one, accents included, for the rest. */
private fun attachment(name: String): String {
    val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}
