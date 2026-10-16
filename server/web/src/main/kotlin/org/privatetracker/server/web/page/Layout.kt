package org.privatetracker.server.web.page

import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HTMLTag
import kotlinx.html.MAIN
import kotlinx.html.ScriptType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.unsafe
import java.time.Instant
import java.time.ZoneId

/** The tabs of the header, in order. */
enum class Section(val path: String, val label: String) {
    MAP("/", "Mapa"),
    DEVICES("/devices", "Dispositivos"),
    GEOFENCES("/geofences", "Geocercas"),
    SERVER("/server", "Servidor"),
}

/** What every page needs to know about the request it answers. */
class PageContext(
    val assets: Assets,
    /** The signed-in account; null on the sign-in page. */
    val account: String?,
    val serverName: String,
    /** The browser's time zone, as its cookie says; else the server's. */
    val zone: ZoneId,
    val now: Instant,
)

/** htmx's settings: no eval, no inline styles of its own (the CSP forbids them), requests to this server only. */
private const val HTMX_CONFIG =
    """{"allowEval":false,"allowScriptTags":false,"includeIndicatorStyles":false,"selfRequestsOnly":true,"historyCacheSize":0}"""

/**
 * The page around every screen: the header with the tabs and the account, the content, the place
 * where alerts appear, and the scripts. No inline script or style anywhere: the CSP allows neither.
 */
fun HTML.page(
    ctx: PageContext,
    title: String,
    section: Section?,
    maps: Boolean = false,
    scripts: List<String> = emptyList(),
    fullScreen: Boolean = false,
    content: MAIN.() -> Unit,
) {
    lang = "es"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        meta(name = "color-scheme", content = "light dark")
        meta(name = "htmx-config", content = HTMX_CONFIG)
        title("$title · ${ctx.serverName}")
        link(rel = "icon", href = ctx.assets.url(Assets.FAVICON), type = "image/svg+xml")
        link(rel = "stylesheet", href = ctx.assets.url(Assets.CSS))
        if (maps) link(rel = "stylesheet", href = ctx.assets.url(Assets.MAPLIBRE_CSS))
    }
    body {
        if (fullScreen) classes = setOf("full")
        ctx.account?.let { attributes["data-signed-in"] = it }
        header("top") {
            a(href = "/", classes = "brand") {
                span("brand-mark") { attributes["aria-hidden"] = "true" }
                span("brand-name") { +ctx.serverName }
            }
            if (ctx.account != null) {
                nav("tabs") {
                    attributes["aria-label"] = "Secciones"
                    Section.entries.forEach { tab ->
                        a(href = tab.path) {
                            if (tab == section) attributes["aria-current"] = "page"
                            +tab.label
                        }
                    }
                }
                div("account") {
                    button(type = ButtonType.button, classes = "ghost small") {
                        id = "notify-enable"
                        attributes["hidden"] = ""
                        +"Activar avisos"
                    }
                    a(href = "/account", classes = "account-name") {
                        attributes["title"] = "Tu cuenta"
                        +ctx.account
                    }
                    form(action = "/logout", method = FormMethod.post, classes = "inline") {
                        button(type = ButtonType.submit, classes = "ghost small") { +"Salir" }
                    }
                }
            }
        }
        main {
            if (fullScreen) classes = setOf("full")
            content()
        }
        div("toasts") {
            id = "toasts"
            attributes["aria-live"] = "polite"
        }
        script(ScriptType.textJavaScript, ctx.assets.url(Assets.HTMX)) {}
        script(ScriptType.textJavaScript, ctx.assets.url(Assets.APP_JS)) {}
        if (maps) {
            script(ScriptType.textJavaScript, ctx.assets.url(Assets.MAPLIBRE_JS)) {}
            script(ScriptType.textJavaScript, ctx.assets.url(Assets.MAPS_JS)) {}
        }
        scripts.forEach { script(ScriptType.textJavaScript, ctx.assets.url(it)) {} }
    }
}

/** The map's settings, for maps.js: the tiles, their attribution and where MapLibre's worker is. */
fun HTMLTag.mapAttributes(ctx: PageContext, tiles: org.privatetracker.server.web.MapTiles) {
    attributes["data-tiles"] = tiles.urlTemplate
    attributes["data-attribution"] = tiles.attribution
    tiles.attributionUrl?.let { attributes["data-attribution-url"] = it }
    attributes["data-worker"] = ctx.assets.url(Assets.MAPLIBRE_WORKER)
}

/**
 * Data for a page's script, as JSON in a script element the browser never runs. `<` is escaped, so no
 * name a device sent can close the element early.
 */
fun FlowContent.jsonData(id: String, json: String) {
    script(type = "application/json") {
        this.id = id
        unsafe { +json.replace("<", "\\u003c") }
    }
}

/** An htmx attribute, as `hx-<name>`. */
fun HTMLTag.hx(name: String, value: String) {
    attributes["hx-$name"] = value
}
