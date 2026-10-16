package org.privatetracker.server.web.page

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import java.security.MessageDigest

/**
 * The web app's own scripts and styles and the browser libraries it ships, read from the jar once.
 * Their URLs carry a hash of all of them, so browsers keep them for a year and still load the new
 * ones right after an upgrade.
 */
class Assets private constructor(private val files: Map<String, Asset>, val version: String) {
    class Asset(val bytes: ByteArray, val contentType: ContentType)

    operator fun get(path: String): Asset? = files[path]

    fun url(path: String): String {
        require(path in files) { "No asset $path" }
        return "/assets/$version/$path"
    }

    companion object {
        const val CSS = "app.css"
        const val APP_JS = "app.js"
        const val MAPS_JS = "maps.js"
        const val LIVE_MAP_JS = "live-map.js"
        const val HISTORY_JS = "history.js"
        const val GEOFENCE_EDITOR_JS = "geofence-editor.js"
        const val FAVICON = "favicon.svg"
        const val HTMX = "vendor/htmx.org/htmx.min.js"
        const val MAPLIBRE_JS = "vendor/maplibre-gl/maplibre-gl-csp.js"
        const val MAPLIBRE_WORKER = "vendor/maplibre-gl/maplibre-gl-csp-worker.js"
        const val MAPLIBRE_CSS = "vendor/maplibre-gl/maplibre-gl.css"
        const val MAPLIBRE_LICENSE = "vendor/maplibre-gl/LICENSE.txt"
        const val HTMX_LICENSE = "vendor/htmx.org/LICENSE"

        private val ALL = listOf(
            CSS, APP_JS, MAPS_JS, LIVE_MAP_JS, HISTORY_JS, GEOFENCE_EDITOR_JS, FAVICON,
            HTMX, MAPLIBRE_JS, MAPLIBRE_WORKER, MAPLIBRE_CSS, MAPLIBRE_LICENSE, HTMX_LICENSE,
        )

        fun load(): Assets {
            val digest = MessageDigest.getInstance("SHA-256")
            val files = ALL.associateWith { path ->
                val bytes = checkNotNull(Assets::class.java.classLoader.getResourceAsStream("static/$path")) { "Missing static/$path" }
                    .use { it.readBytes() }
                digest.update(path.encodeToByteArray())
                digest.update(bytes)
                Asset(bytes, contentTypeOf(path))
            }
            val version = digest.digest().take(6).joinToString("") { "%02x".format(it) }
            return Assets(files, version)
        }

        private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.')) {
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            "js" -> ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
            "svg" -> ContentType.Image.SVG
            else -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
        }
    }
}
