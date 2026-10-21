package org.privatetracker.server.linux

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.normalizeFingerprint
import org.privatetracker.core.domain.validation.ServerConfigValidator
import org.privatetracker.core.domain.validation.TrackerConfigValidator
import org.privatetracker.server.linux.keys.PemFiles
import org.privatetracker.server.linux.platform.WebhookFormat
import org.privatetracker.server.linux.platform.WebhookSettings
import org.privatetracker.server.web.MapTiles
import java.net.URI
import java.nio.file.Path

/** A command line: the command, its positional words, and `--name value` / `--flag` options. */
class Arguments(val command: String?, val positional: List<String>, private val options: Map<String, List<String>>) {
    fun value(name: String): String? = options[name]?.last()
    fun values(name: String): List<String> = options[name].orEmpty()
    fun flag(name: String): Boolean = name in options
    val names: Set<String> get() = options.keys

    companion object {
        /** Options that take no value; every other `--option` takes the next word. */
        private val FLAGS = setOf("all-pending", "quiet", "help", "version")

        fun parse(args: List<String>): Arguments {
            var command: String? = null
            val positional = mutableListOf<String>()
            val options = linkedMapOf<String, MutableList<String>>()
            var i = 0
            while (i < args.size) {
                val word = args[i]
                when {
                    word.startsWith("--") -> {
                        val name = word.removePrefix("--").substringBefore('=')
                        val inline = word.substringAfter('=', missingDelimiterValue = "").takeIf { '=' in word }
                        val value = when {
                            inline != null -> inline
                            name in FLAGS -> ""
                            i + 1 < args.size -> args[++i]
                            else -> throw UsageException("Falta el valor de --$name")
                        }
                        options.getOrPut(name) { mutableListOf() }.add(value)
                    }
                    word == "-h" -> options.getOrPut("help") { mutableListOf() }.add("")
                    command == null -> command = word
                    else -> positional += word
                }
                i++
            }
            return Arguments(command, positional, options)
        }
    }
}

class UsageException(message: String) : Exception(message)

/**
 * What `serve` runs with: the domain's server settings, the URLs that invites hand to trackers, and
 * from 1.0 the web app ([web] null: off), its map tiles and where geofence alerts also go.
 */
data class ServeSettings(
    val config: ServerConfig,
    val publicUrls: List<String>,
    val web: WebSettings? = null,
    val tiles: MapTiles = MapTiles.OPEN_STREET_MAP,
    val webhook: WebhookSettings? = null,
)

/** The web app's port. Without [certificate], it shows the self-signed certificate of the server's identity. */
data class WebSettings(val port: Int = DEFAULT_WEB_PORT, val bindAddress: String = "0.0.0.0", val certificate: PemFiles? = null) {
    companion object {
        const val DEFAULT_WEB_PORT = 8788
    }
}

/**
 * `serve`'s settings: each option from its flag, else from its PRIVATETRACKER_ variable, else the
 * phone server's default. Checked with the same rules as the phone's settings screen.
 */
fun serveSettings(arguments: Arguments, environment: Map<String, String>): ServeSettings {
    fun setting(option: String): String? =
        arguments.value(option) ?: environment["PRIVATETRACKER_" + option.uppercase().replace('-', '_')]?.takeIf { it.isNotBlank() }

    fun number(option: String, default: Int): Int =
        setting(option)?.let { it.trim().toIntOrNull() ?: throw UsageException("--$option debe ser un número: «$it»") } ?: default

    fun yesNo(option: String, default: Boolean): Boolean = when (setting(option)?.trim()?.lowercase()) {
        null -> default
        "true", "yes", "1", "si", "sí" -> true
        "false", "no", "0" -> false
        else -> throw UsageException("--$option debe ser true o false")
    }

    val defaults = ServerConfig()
    val config = ServerConfig(
        serverName = setting("name")?.trim() ?: defaults.serverName,
        port = number("port", defaults.port),
        bindAddress = setting("bind")?.trim() ?: defaults.bindAddress,
        autoStart = true,
        onlineThresholdSeconds = number("online-threshold", defaults.onlineThresholdSeconds),
        retentionDays = number("retention-days", defaults.retentionDays),
        // Off, only trackers paired with `pair` get in: a host open to the internet gets no list of strangers.
        acceptNewDevices = yesNo("accept-new-devices", defaults.acceptNewDevices),
        // A host has no mDNS announcer yet; trackers find it by the URLs of their invite.
        advertiseOnLan = false,
        relayAddress = setting("relay")?.trim()?.lowercase().orEmpty(),
        relayFingerprint = setting("relay-fingerprint")?.let(::normalizeFingerprint).orEmpty(),
        relayWeb = yesNo("relay-web", false),
    )
    ServerConfigValidator.validate(config).firstOrNull()?.let { throw UsageException(describe(it)) }

    val web = webSettings(::setting, config)
    if (config.relayWeb && (config.relay == null || web == null)) {
        throw UsageException("--relay-web pide un relay (--relay) y la app web encendida")
    }
    val tiles = tiles(::setting)
    val webhook = webhookSettings(::setting)

    val rawUrls = arguments.values("public-url").ifEmpty {
        environment["PRIVATETRACKER_PUBLIC_URLS"].orEmpty().split(',', ' ').filter { it.isNotBlank() }
    }
    val publicUrls = rawUrls.map { raw ->
        val url = TrackerConfigValidator.normalizeServerUrl(raw)
        TrackerConfigValidator.validateServerUrl(url)?.let { throw UsageException("URL pública no válida «$raw»: ${describe(it)}") }
        url
    }.distinct()
    return ServeSettings(config, publicUrls, web, tiles, webhook)
}

/** `--web-port 0` turns the web app off. */
private fun webSettings(setting: (String) -> String?, config: ServerConfig): WebSettings? {
    val rawPort = setting("web-port")?.trim()
    val port = rawPort?.let { it.toIntOrNull() ?: throw UsageException("--web-port debe ser un número: «$it»") } ?: WebSettings.DEFAULT_WEB_PORT
    if (port == 0) return null
    if (port !in ServerConfigValidator.PORT) {
        throw UsageException("--web-port debe estar entre ${ServerConfigValidator.PORT.first} y ${ServerConfigValidator.PORT.last}, o 0 para apagar la app web")
    }
    val bind = setting("web-bind")?.trim() ?: config.bindAddress
    if (port == config.port && (bind == config.bindAddress || bind == "0.0.0.0" || config.bindAddress == "0.0.0.0")) {
        throw UsageException("--web-port debe ser distinto de --port (${config.port}): los Trackers y el navegador usan puertos separados")
    }
    val certificate = setting("web-cert")?.trim()
    val key = setting("web-key")?.trim()
    if ((certificate == null) != (key == null)) throw UsageException("--web-cert y --web-key van juntos: el certificado y su clave privada")
    return WebSettings(port, bind, if (certificate != null && key != null) PemFiles(Path.of(certificate), Path.of(key)) else null)
}

private fun tiles(setting: (String) -> String?): MapTiles {
    val url = setting("tile-url")?.trim() ?: return MapTiles.OPEN_STREET_MAP.let { default ->
        setting("tile-attribution")?.let { default.copy(attribution = it, attributionUrl = null) } ?: default
    }
    val parsed = runCatching { URI(url.replace("{", "").replace("}", "")) }.getOrNull()
    if (parsed?.scheme != "https" || parsed.host.isNullOrEmpty() || listOf("{z}", "{x}", "{y}").any { it !in url }) {
        throw UsageException("--tile-url debe ser https:// y llevar {z}, {x} y {y}, p. ej. https://tiles.example.org/{z}/{x}/{y}.png")
    }
    return MapTiles(url, setting("tile-attribution")?.trim() ?: "© OpenStreetMap", null)
}

private fun webhookSettings(setting: (String) -> String?): WebhookSettings? {
    val raw = setting("alert-webhook")?.trim() ?: return null
    val url = runCatching { URI(raw) }.getOrNull()
    if (url == null || url.scheme !in setOf("https", "http") || url.host.isNullOrEmpty()) {
        throw UsageException("--alert-webhook debe ser una URL https:// (o http:// en tu red), p. ej. https://ntfy.sh/mi-tema")
    }
    val format = when (setting("alert-webhook-format")?.trim()?.lowercase()) {
        null -> if (url.host == "ntfy.sh" || url.host.startsWith("ntfy.")) WebhookFormat.NTFY else WebhookFormat.JSON
        "json" -> WebhookFormat.JSON
        "ntfy" -> WebhookFormat.NTFY
        else -> throw UsageException("--alert-webhook-format debe ser json o ntfy")
    }
    return WebhookSettings(url, format, setting("alert-webhook-token")?.trim()?.ifEmpty { null })
}

private fun describe(violation: FieldViolation): String {
    val option = when (violation.field) {
        "serverName" -> "--name"
        "port" -> "--port"
        "bindAddress" -> "--bind"
        "onlineThresholdSeconds" -> "--online-threshold"
        "retentionDays" -> "--retention-days"
        "serverUrl" -> "la URL"
        "relayAddress" -> "--relay"
        "relayFingerprint" -> "--relay-fingerprint"
        else -> violation.field
    }
    val rule = when (violation.rule) {
        FieldViolation.REQUIRED -> "no puede quedar vacío"
        FieldViolation.TOO_LONG -> "es demasiado largo"
        FieldViolation.OUT_OF_RANGE -> when (violation.field) {
            "port" -> "debe estar entre ${ServerConfigValidator.PORT.first} y ${ServerConfigValidator.PORT.last}"
            "onlineThresholdSeconds" ->
                "debe estar entre ${ServerConfigValidator.ONLINE_THRESHOLD_SECONDS.first} y ${ServerConfigValidator.ONLINE_THRESHOLD_SECONDS.last} s"
            "retentionDays" -> "debe estar entre ${ServerConfigValidator.RETENTION_DAYS.first} y ${ServerConfigValidator.RETENTION_DAYS.last} días"
            else -> "está fuera de rango"
        }
        FieldViolation.HTTPS_REQUIRED -> "debe empezar con https://"
        else -> "no tiene un formato válido"
    }
    return "$option $rule"
}
