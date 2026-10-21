package org.privatetracker.server.relay

import org.privatetracker.core.domain.model.RelayAddress
import org.privatetracker.core.domain.model.isValidFingerprint
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.normalizeFingerprint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.server.hostkeys.IdentityKeyFile
import org.privatetracker.server.hostkeys.lockFile
import org.privatetracker.server.hostkeys.prepareOwnerOnlyDirectory
import org.privatetracker.server.tunnel.RelayTrustManager
import org.privatetracker.server.tunnel.Tunnel
import org.privatetracker.server.tunnel.TunnelMessage
import org.privatetracker.server.tunnel.closeQuietly
import org.privatetracker.server.tunnel.useModernProtocols
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.system.exitProcess

private const val USAGE = """Uso: privatetracker-relay <orden> [opciones]

Un relay deja que los Trackers lleguen a un servidor PrivateTracker detrás de NAT o CGNAT. El servidor
abre un túnel hacia el relay; el relay pasa la conexión TLS de cada Tracker por ese túnel sin abrirla.

Órdenes:
  serve                 Arranca el relay (lo que corre la imagen de Docker)
  fingerprint           La huella de la clave del relay, para configurar los servidores
  health                ¿Responde el relay de este equipo? Con --quiet solo el código de salida

Opciones de todas las órdenes:
  --data <dir>          Directorio de datos (PRIVATETRACKER_RELAY_DATA; por omisión ~/.local/share/privatetracker-relay)

Opciones de serve y health (o su variable de entorno):
  --domain <nombre>     Nombre del relay, p. ej. relay.example.org (PRIVATETRACKER_RELAY_DOMAIN; obligatoria).
                        El DNS debe llevar ese nombre y *.ese-nombre a este equipo
  --port <n>            Puerto (PRIVATETRACKER_RELAY_PORT; 443)
  --bind <ip>           Dirección donde escuchar (PRIVATETRACKER_RELAY_BIND; 0.0.0.0)
  --allow <huella>      Huella de un servidor que puede usar el relay; repetible
                        (PRIVATETRACKER_RELAY_ALLOW, separadas por comas; al menos una)
"""

private const val EXIT_ERROR = 1
private const val EXIT_USAGE = 2
private const val EXIT_NOT_RUNNING = 3

private class UsageException(message: String) : Exception(message)

fun main(args: Array<String>) {
    val options = try {
        parse(args.toList())
    } catch (e: UsageException) {
        fail(e.message, EXIT_USAGE)
    }
    val command = options.command
    if ("help" in options.flags || command == null || command == "help") {
        print(USAGE)
        return
    }
    if ("version" in options.flags) {
        println("PrivateTracker Relay ${Relay::class.java.`package`?.implementationVersion ?: "dev"}")
        return
    }
    val environment = System.getenv()
    val data = dataDirectory(options.value("data"), environment)
    try {
        when (command) {
            "serve" -> serve(settings(options, environment), data)
            "fingerprint" -> {
                // On a new data directory the key is made here, so the owner can hand its fingerprint out before the first start.
                prepare(data)
                println(keyFingerprint(identity(data).publicKey()) ?: fail("La clave del relay no se puede leer", EXIT_ERROR))
            }
            "health" -> health(settings(options, environment, needsAllowList = false), data, quiet = "quiet" in options.flags)
            else -> fail("Orden desconocida «$command». Mira: privatetracker-relay --help", EXIT_USAGE)
        }
    } catch (e: UsageException) {
        fail(e.message, EXIT_USAGE)
    } catch (e: DeviceKeyException) {
        fail("La clave del relay (${data.resolve(IDENTITY_FILE)}) no se puede usar: ${e.message}. No se crea otra, porque los servidores la conocen.", EXIT_ERROR)
    }
}

private const val IDENTITY_FILE = "relay-identity.p12"

private fun identity(data: Path) = IdentityKeyFile(data.resolve(IDENTITY_FILE), "privatetracker-relay", "PrivateTracker Relay", Instant::now)

private fun fail(message: String?, code: Int): Nothing {
    System.err.println(message)
    exitProcess(code)
}

private class Options(val command: String?, val values: Map<String, List<String>>, val flags: Set<String>) {
    fun value(name: String): String? = values[name]?.last()
    fun all(name: String): List<String> = values[name].orEmpty()
}

private val FLAGS = setOf("quiet", "help", "version")

private fun parse(args: List<String>): Options {
    var command: String? = null
    val values = linkedMapOf<String, MutableList<String>>()
    val flags = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        val word = args[i]
        when {
            word.startsWith("--") -> {
                val name = word.removePrefix("--").substringBefore('=')
                when {
                    '=' in word -> values.getOrPut(name) { mutableListOf() } += word.substringAfter('=')
                    name in FLAGS -> flags += name
                    i + 1 < args.size -> values.getOrPut(name) { mutableListOf() } += args[++i]
                    else -> throw UsageException("Falta el valor de --$name")
                }
            }
            word == "-h" -> flags += "help"
            command == null -> command = word
            else -> throw UsageException("No sé qué hacer con «$word»")
        }
        i++
    }
    return Options(command, values, flags)
}

private fun dataDirectory(option: String?, environment: Map<String, String>): Path {
    val path = option ?: environment["PRIVATETRACKER_RELAY_DATA"]?.takeIf { it.isNotBlank() }
        ?: Path.of(environment["XDG_DATA_HOME"] ?: "${System.getProperty("user.home")}/.local/share", "privatetracker-relay").toString()
    return Path.of(path).toAbsolutePath().normalize()
}

private fun settings(options: Options, environment: Map<String, String>, needsAllowList: Boolean = true): RelaySettings {
    fun setting(name: String): String? =
        options.value(name) ?: environment["PRIVATETRACKER_RELAY_" + name.uppercase().replace('-', '_')]?.takeIf { it.isNotBlank() }

    val rawDomain = setting("domain") ?: throw UsageException("Falta --domain: el nombre del relay, p. ej. relay.example.org")
    val domain = RelayAddress.parse(rawDomain)?.takeIf { ':' !in rawDomain }?.host
        ?: throw UsageException("--domain debe ser un nombre de dominio, sin puerto ni dirección IP: «$rawDomain»")
    val port = setting("port")?.let { it.trim().toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw UsageException("--port debe ser un número entre 1 y 65535") }
        ?: RelaySettings.DEFAULT_PORT
    val bind = setting("bind")?.trim() ?: "0.0.0.0"
    val raw = options.all("allow").ifEmpty { environment["PRIVATETRACKER_RELAY_ALLOW"].orEmpty().split(',', ' ').filter { it.isNotBlank() } }
    val allowed = raw.map(::normalizeFingerprint).onEach {
        if (!isValidFingerprint(it)) throw UsageException("«$it» no es una huella: son 16 dígitos hexadecimales, como 3F9A-01BC-77D2-E410")
    }.toSet()
    if (needsAllowList && allowed.isEmpty()) {
        throw UsageException("Falta --allow con la huella de al menos un servidor (privatetracker-server fingerprint, o la pantalla Servidor del teléfono)")
    }
    return RelaySettings(domain, port, bind, allowed)
}

private fun prepare(data: Path) {
    try {
        prepareOwnerOnlyDirectory(data)
    } catch (e: IOException) {
        fail("No se puede usar $data: ${e.message}", EXIT_ERROR)
    }
}

/** Runs until SIGTERM or Ctrl+C. */
private fun serve(settings: RelaySettings, data: Path) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv("PRIVATETRACKER_LOG_LEVEL") ?: "info")
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss")
    System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
    val log = LoggerFactory.getLogger("Main")
    prepare(data)
    val lock = lockFile(data.resolve("relay.lock")) ?: fail("Ya hay un relay corriendo con los datos de $data.", EXIT_ERROR)
    val identity = identity(data)
    val relay = Relay(settings, identity.keyManager())
    try {
        relay.start()
    } catch (e: BindException) {
        fail("El puerto ${settings.port} ya está en uso, o este usuario no puede abrirlo (los menores que 1024 piden CAP_NET_BIND_SERVICE).", EXIT_ERROR)
    }
    log.info("Relay key fingerprint {}; servers set it with: --relay {} --relay-fingerprint {}",
        keyFingerprint(identity.publicKey()), RelayAddress(settings.domain, settings.port), keyFingerprint(identity.publicKey()))
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            relay.stop()
            lock.close()
            stopped.countDown()
        },
    )
    stopped.await()
}

/** Asks the relay on this machine, as a server would, whether it answers; for Docker's HEALTHCHECK. */
private fun health(settings: RelaySettings, data: Path, quiet: Boolean) {
    val fingerprint = keyFingerprint(identity(data).publicKey()) ?: fail("La clave del relay no se puede leer", EXIT_ERROR)
    val host = if (settings.bindAddress == "0.0.0.0" || settings.bindAddress == "::") "127.0.0.1" else settings.bindAddress
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(RelayTrustManager(fingerprint)), null) }
    val raw = Socket()
    try {
        raw.connect(InetSocketAddress(host, settings.port), 5_000)
        raw.soTimeout = 10_000
        val socket = context.socketFactory.createSocket(raw, settings.domain, settings.port, true) as SSLSocket
        socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(settings.domain)) }
        socket.useModernProtocols()
        Tunnel.write(socket.outputStream, TunnelMessage.Health)
        if (Tunnel.read(socket.inputStream) != TunnelMessage.Ok) fail("El relay respondió algo inesperado", EXIT_ERROR)
        if (!quiet) println("El relay responde en $host:${settings.port} con la huella $fingerprint.")
    } catch (e: IOException) {
        if (!quiet) System.err.println("El relay no responde en $host:${settings.port}: ${e.message}")
        exitProcess(EXIT_NOT_RUNNING)
    } finally {
        closeQuietly(raw)
    }
}
