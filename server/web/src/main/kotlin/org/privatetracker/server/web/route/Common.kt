package org.privatetracker.server.web.route

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.GeofenceId
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.server.web.WebDependencies
import org.privatetracker.server.web.page.Assets
import org.privatetracker.server.web.page.PageContext
import org.privatetracker.server.web.security.zone

internal suspend fun ApplicationCall.pageContext(deps: WebDependencies, assets: Assets, session: WebSession?): PageContext =
    PageContext(assets, session?.account, deps.serverConfig.get().serverName, zone(), deps.clock.now())

/** After a form: 303, so a reload of the next page does not send the form again. */
internal suspend fun ApplicationCall.seeOther(path: String) {
    response.header(HttpHeaders.Location, path)
    respond(HttpStatusCode.SeeOther)
}

/** A piece of a page for htmx to swap in: what [block] draws, without the element kotlinx.html needs around it. */
internal suspend fun ApplicationCall.respondFragment(status: HttpStatusCode = HttpStatusCode.OK, block: FlowContent.() -> Unit) {
    val wrapped = createHTML(prettyPrint = false).div { block() }
    respondText(wrapped.removePrefix("<div>").removeSuffix("</div>"), ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

internal fun ApplicationCall.deviceIdParameter(): DeviceId = DeviceId.parse(parameters["id"].orEmpty()) ?: throw NotFoundException()

internal fun ApplicationCall.geofenceIdParameter(): GeofenceId = GeofenceId.parse(parameters["id"].orEmpty()) ?: throw NotFoundException()

/** Pages show what someone may not see after signing out, so no browser keeps a copy of them. */
internal fun ApplicationCall.noStore() = response.cacheControl(CacheControl.NoStore(null))

/** The files of [Assets]: a year in the cache under the current version, not at all under an old one. */
internal fun Route.assetRoutes(assets: Assets) {
    get("/assets/{version}/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        val asset = assets[path] ?: throw NotFoundException()
        if (call.parameters["version"] == assets.version) {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        } else {
            call.noStore()
        }
        call.respond(io.ktor.http.content.ByteArrayContent(asset.bytes, asset.contentType))
    }
    get("/robots.txt") {
        call.respond(io.ktor.http.content.TextContent("User-agent: *\nDisallow: /\n", ContentType.Text.Plain))
    }
}
