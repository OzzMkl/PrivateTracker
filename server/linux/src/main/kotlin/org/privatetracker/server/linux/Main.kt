package org.privatetracker.server.linux

import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.server.linux.admin.AdminClient
import org.privatetracker.server.linux.admin.AdminReply
import org.privatetracker.server.linux.admin.AdminRequest
import org.privatetracker.server.linux.admin.AlreadyRunningException
import org.privatetracker.server.linux.admin.DeviceRow
import org.privatetracker.server.linux.admin.PeerRow
import org.privatetracker.server.linux.admin.ServerNotAnsweringException
import org.privatetracker.server.linux.admin.ServerNotRunningException
import org.privatetracker.server.linux.keys.WebCertificateException
import org.privatetracker.server.web.qrMatrix
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.BindException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE = """Uso: privatetracker-server <orden> [opciones]

Órdenes:
  serve                 Arranca el servidor (lo que corre la imagen de Docker)
  pair                  Muestra un código QR para emparejar un teléfono y espera a que lo use
  devices               Lista los dispositivos
  approve <disp.>       Aprueba un dispositivo; --all-pending aprueba todos los que esperan
  reject <disp.>        Rechaza un dispositivo: conserva su historial y no acepta sus posiciones
  remove <disp.>        Borra un dispositivo con todo su historial
  rotate-key            Cambia ya la clave de cifrado y borra las anteriores
  status                Estado del servidor; con --quiet solo el código de salida
  fingerprint           La huella de la clave del servidor, para configurar un Tracker a mano
  users                 Lista las cuentas de la app web
  users add <nombre>    Crea una cuenta de la app web; pide la contraseña (15 caracteres o más)
  users passwd <nombre> Cambia la contraseña de una cuenta y cierra todas sus sesiones
  users remove <nombre> Borra una cuenta y cierra todas sus sesiones
  peers                 Lista los servidores emparejados con este
  peers invite          Muestra una invitación (QR y enlace) para que otro servidor se empareje y espera
  peers join            Se empareja con el servidor de una invitación (privatetracker://peer?...): pide el
                        enlace, que así no queda en el historial ni a la vista de otros usuarios con ps
  peers remove <servidor>  Deja de compartir con ese servidor (su nombre exacto o su huella)

Dos servidores emparejados comparten en ambos sentidos sus dispositivos y posiciones, cada minuto.
Geocercas, cuentas de la app web y claves siguen siendo de cada uno.

<disp.> es el id de un dispositivo, sus primeros caracteres (4 o más) o su nombre exacto.

Opciones de todas las órdenes:
  --data <dir>          Directorio de datos (PRIVATETRACKER_DATA; por omisión ~/.local/share/privatetracker)

Opciones de serve (o su variable de entorno):
  --port <n>            Puerto HTTPS (PRIVATETRACKER_PORT; 8787)
  --bind <ip>           Dirección donde escuchar (PRIVATETRACKER_BIND; 0.0.0.0)
  --name <nombre>       Nombre que ven los Trackers (PRIVATETRACKER_NAME)
  --public-url <url>    URL con la que los teléfonos llegan al servidor; repetible
                        (PRIVATETRACKER_PUBLIC_URLS, separadas por comas)
  --retention-days <n>  Días que se guardan las posiciones (PRIVATETRACKER_RETENTION_DAYS; 30)
  --online-threshold <s> Segundos sin noticias para dejar de verlo en línea (PRIVATETRACKER_ONLINE_THRESHOLD; 300)
  --accept-new-devices <true|false>  Si un Tracker configurado a mano puede pedir entrar (PRIVATETRACKER_ACCEPT_NEW_DEVICES;
                        true). Con false solo entran los emparejados con pair.

Relay, para llegar al servidor desde fuera sin abrir puertos (privatetracker-relay en un VPS):
  --relay <nombre[:puerto]>  El relay, p. ej. relay.example.org (PRIVATETRACKER_RELAY)
  --relay-fingerprint <huella>  La huella de la clave del relay (PRIVATETRACKER_RELAY_FINGERPRINT)
  --relay-web <true|false>  También la app web por el relay (PRIVATETRACKER_RELAY_WEB; false)

App web (en su propio puerto; los Trackers no lo usan):
  --web-port <n>        Puerto HTTPS de la app web (PRIVATETRACKER_WEB_PORT; 8788). 0 la apaga
  --web-bind <ip>       Dirección donde escucha (PRIVATETRACKER_WEB_BIND; la de --bind)
  --web-cert <archivo>  Certificado PEM propio, p. ej. de tailscale cert o Let's Encrypt (PRIVATETRACKER_WEB_CERT).
                        Sin él, la app web usa el certificado autofirmado del servidor y el navegador avisa
  --web-key <archivo>   Su clave privada PEM, sin contraseña (PRIVATETRACKER_WEB_KEY)
  --tile-url <url>      Teselas del mapa, https://…/{z}/{x}/{y}.png (PRIVATETRACKER_TILE_URL; las de OpenStreetMap)
  --tile-attribution <texto>  Atribución que exigen esas teselas (PRIVATETRACKER_TILE_ATTRIBUTION)

Avisos de geocercas con la app web cerrada:
  --alert-webhook <url> URL que recibe cada cruce: un tema de ntfy, Gotify, Home Assistant… (PRIVATETRACKER_ALERT_WEBHOOK)
  --alert-webhook-format <json|ntfy>  Formato del cuerpo (PRIVATETRACKER_ALERT_WEBHOOK_FORMAT; ntfy si el host es ntfy.*)
  --alert-webhook-token <token>  Se envía como Authorization: Bearer (PRIVATETRACKER_ALERT_WEBHOOK_TOKEN; mejor
                        por variable que por opción, que otros usuarios ven con ps)

Opciones de pair y de peers invite:
  --png <archivo>       Guarda además el código QR como imagen PNG
"""

private const val EXIT_ERROR = 1
private const val EXIT_USAGE = 2
private const val EXIT_NOT_RUNNING = 3

fun main(args: Array<String>) {
    val arguments = try {
        Arguments.parse(args.toList())
    } catch (e: UsageException) {
        fail(e.message, EXIT_USAGE)
    }
    if (arguments.flag("version")) {
        println("PrivateTracker Server ${version()}")
        return
    }
    if (arguments.flag("help") || arguments.command == null || arguments.command == "help") {
        print(USAGE)
        return
    }
    val environment = System.getenv()
    val data = DataDirectory.locate(arguments.value("data"), environment)
    try {
        when (arguments.command) {
            "serve" -> serve(arguments, environment, data)
            "pair" -> pair(data, arguments.value("png")?.let(Path::of))
            "devices" -> printDevices(expect<AdminReply.Devices>(data, AdminRequest.Devices).devices)
            "approve" -> {
                val request = if (arguments.flag("all-pending")) AdminRequest.Approve(allPending = true) else AdminRequest.Approve(device(arguments))
                val done = expect<AdminReply.Done>(data, request)
                if (done.devices.isEmpty()) println("Ningún dispositivo esperaba aprobación.") else done.devices.forEach { println("Aprobado: ${it.describe()}") }
            }
            "reject" -> expect<AdminReply.Done>(data, AdminRequest.Reject(device(arguments))).devices.forEach { println("Rechazado: ${it.describe()}") }
            "remove" -> expect<AdminReply.Done>(data, AdminRequest.Remove(device(arguments))).devices.forEach { println("Borrado con su historial: ${it.describe()}") }
            "rotate-key" -> println("Nueva clave de cifrado: ${expect<AdminReply.Done>(data, AdminRequest.RotateKey).encryptionKeyId}. Los Trackers la piden solos.")
            "status" -> {
                val status = expect<AdminReply.Status>(data, AdminRequest.Status)
                if (!arguments.flag("quiet")) printStatus(status)
            }
            "fingerprint" -> println(expect<AdminReply.Status>(data, AdminRequest.Status).fingerprint ?: fail("La clave del servidor no responde", EXIT_ERROR))
            "users" -> users(arguments, data)
            "peers" -> peers(arguments, data)
            else -> fail("Orden desconocida «${arguments.command}». Mira: privatetracker-server --help", EXIT_USAGE)
        }
    } catch (e: UsageException) {
        fail(e.message, EXIT_USAGE)
    } catch (e: ServerNotRunningException) {
        fail("El servidor no está corriendo con los datos de ${data.root} (no responde en ${e.socket}).", EXIT_NOT_RUNNING)
    } catch (e: ServerNotAnsweringException) {
        fail("El servidor aceptó la orden pero no respondió a tiempo; revisa su registro.", EXIT_ERROR)
    }
}

private fun device(arguments: Arguments): String =
    arguments.positional.singleOrNull() ?: throw UsageException("Indica un dispositivo: su id, sus primeros caracteres o su nombre")

private fun version(): String = ServerGraph::class.java.`package`?.implementationVersion ?: "dev"

private fun fail(message: String?, code: Int): Nothing {
    System.err.println(message)
    exitProcess(code)
}

/** Runs until SIGTERM or Ctrl+C, then stops the way a phone server does. */
private fun serve(arguments: Arguments, environment: Map<String, String>, data: DataDirectory) {
    configureLogging(environment["PRIVATETRACKER_LOG_LEVEL"] ?: "info")
    // Ktor would stop Netty from a shutdown hook of its own, racing ours, which answers 503 first and closes sessions after.
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    val log = LoggerFactory.getLogger("Main")
    val settings = serveSettings(arguments, environment)
    try {
        data.prepare()
    } catch (e: IOException) {
        fail("No se puede usar ${data.root}: ${e.message}", EXIT_ERROR)
    }
    val graph = ServerGraph(settings, data)
    val server = LinuxServer(graph, version())
    try {
        server.start()
    } catch (e: AlreadyRunningException) {
        graph.close()
        fail("Ya hay un servidor corriendo con los datos de ${data.root}.", EXIT_ERROR)
    } catch (e: WebCertificateException) {
        graph.close()
        fail("El certificado de la app web no se puede usar: ${e.message}. Revisa --web-cert y --web-key.", EXIT_ERROR)
    } catch (e: DeviceKeyException) {
        graph.close()
        log.error("Server key unavailable", e)
        fail("La clave del servidor (${data.identity}) no se puede leer: ${e.message}. No se crea otra, porque los Trackers emparejados la conocen.", EXIT_ERROR)
    } catch (e: UnsatisfiedLinkError) {
        log.error("Native library unavailable", e)
        fail(
            "No se pudo cargar SQLite (${e.message}). Suele pasar cuando ${System.getProperty("java.io.tmpdir")} está montado " +
                "con noexec: monta /tmp con exec o define JAVA_OPTS=-Djava.io.tmpdir=<directorio con exec>.",
            EXIT_ERROR,
        )
    } catch (e: Exception) {
        graph.close()
        if (generateSequence<Throwable>(e) { it.cause }.any { it is BindException }) {
            val ports = listOfNotNull(settings.config.port, settings.web?.port).joinToString(" o el ")
            fail("El puerto $ports ya está en uso.", EXIT_ERROR)
        }
        log.error("Server failed to start", e)
        fail("El servidor no pudo arrancar: ${e.message}", EXIT_ERROR)
    }
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            stopped.countDown()
        },
    )
    stopped.await()
}

/** SLF4J's simple logger, to stderr; set before the first logger is made. */
private fun configureLogging(level: String) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level)
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
    System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss")
    System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
    System.setProperty("org.slf4j.simpleLogger.log.io.netty", "warn")
    // Ktor sees a plain connector and would announce http://; the TLS handler in front is ours, and so is the start line.
    System.setProperty("org.slf4j.simpleLogger.log.io.ktor.server.Application", "warn")
}

private inline fun <reified T : AdminReply> expect(data: DataDirectory, request: AdminRequest): T =
    when (val reply = AdminClient(data.adminSocket).single(request)) {
        is T -> reply
        is AdminReply.Error -> fail(reply.message, EXIT_ERROR)
        else -> fail("Respuesta inesperada del servidor", EXIT_ERROR)
    }

private fun pair(data: DataDirectory, png: Path?) {
    var paired = false
    // An invite lasts 10 minutes; the server ends the conversation by then.
    AdminClient(data.adminSocket).exchange(AdminRequest.Pair, timeout = Duration.ofMinutes(11)) { reply ->
        when (reply) {
            is AdminReply.Invite -> {
                val matrix = qrMatrix(reply.uri)
                println()
                print(terminalQr(matrix))
                println()
                png?.let {
                    writeQrPng(matrix, it)
                    println("Código QR guardado en $it")
                }
                println("En el teléfono, abre PrivateTracker > Rastreo > Escanear QR y apunta la cámara al código.")
                println("O abre este enlace en el teléfono:")
                println("  ${reply.uri}")
                println()
                println("Huella del servidor: ${reply.fingerprint}")
                println("Direcciones:         ${reply.urls.joinToString(", ")}")
                println("Vale hasta las ${clock(Instant.ofEpochMilli(reply.expiresAtMillis))}. Esperando al teléfono... (Ctrl+C cancela)")
            }
            is AdminReply.Paired -> {
                paired = true
                println("Emparejado: ${reply.device.describe()}. Ya está aprobado y puede enviar posiciones.")
            }
            AdminReply.Expired -> println("El código venció sin que ningún teléfono lo usara. Genera otro con: privatetracker-server pair")
            is AdminReply.Error -> fail(reply.message, EXIT_ERROR)
            else -> Unit
        }
    }
    if (!paired) exitProcess(EXIT_ERROR)
}

private fun printDevices(devices: List<DeviceRow>) {
    if (devices.isEmpty()) {
        println("Todavía no hay dispositivos. Empareja uno con: privatetracker-server pair")
        return
    }
    val rows = devices.map {
        listOf(it.id.take(8), printable(it.name), statusLabel(it.status), approvalLabel(it.approval), it.lastSeenAtMillis?.let { ms -> dateTime(Instant.ofEpochMilli(ms)) } ?: "nunca", it.fingerprint ?: "-")
    }
    val header = listOf("ID", "NOMBRE", "ESTADO", "APROBACIÓN", "ÚLTIMA VEZ", "HUELLA")
    val widths = header.indices.map { column -> (rows.map { it[column] } + header[column]).maxOf { it.length } }
    (listOf(header) + rows).forEach { row -> println(row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()) }
}

private fun printStatus(status: AdminReply.Status) {
    val started = Instant.ofEpochMilli(status.startedAtMillis)
    val uptime = Duration.between(started, Instant.now())
    println("${printable(status.serverName)} — PrivateTracker Server ${status.version}")
    println("Corriendo desde:  ${dateTime(started)} (${uptime.toHours()} h ${uptime.toMinutesPart()} min)")
    println("Puerto HTTPS:     ${status.port}")
    println("Huella:           ${status.fingerprint ?: "la clave no responde"}")
    println("Direcciones:      ${status.inviteUrls.ifEmpty { listOf("ninguna; define PRIVATETRACKER_PUBLIC_URLS") }.joinToString(", ")}")
    println("Dispositivos:     ${status.devices} (${status.online} en línea, ${status.pending} esperando aprobación)")
    val key = status.encryptionKeyId?.let { id -> "$id, se usa hasta ${status.encryptionKeyUntilMillis?.let { dateTime(Instant.ofEpochMilli(it)) }}" }
    println("Clave de cifrado: ${key ?: "no disponible"}")
    println("Peticiones:       ${status.requests}")
    if (status.peers > 0) println("Emparejado con:   ${if (status.peers == 1) "1 servidor" else "${status.peers} servidores"} (privatetracker-server peers)")
    status.relay?.let { relay ->
        println("Relay:            $relay, ${status.relayState}")
        status.relayUrl?.let { println("Fuera de casa:    $it") }
    }
    if (status.webPort == null) {
        println("App web:          apagada")
    } else {
        println("App web:          puerto ${status.webPort}, ${if (status.webAccounts == 1) "1 cuenta" else "${status.webAccounts} cuentas"}")
        status.webCertificateSha256?.let { println("Certificado web:  SHA-256 $it") }
    }
}

/** `peers`, `peers invite|join|remove`: paired servers, through the running server (1.0). */
private fun peers(arguments: Arguments, data: DataDirectory) {
    when (val action = arguments.positional.firstOrNull() ?: "list") {
        "list" -> printPeers(expect<AdminReply.Peers>(data, AdminRequest.Peers).peers)
        "invite" -> peerInvite(data, arguments.value("png")?.let(Path::of))
        "join" -> {
            // As an argument it works too, but then the secret shows in ps and stays in the history.
            val link = arguments.positional.getOrNull(1)?.takeIf { it != "-" } ?: readInviteLink()
            val peer = expect<AdminReply.PeerJoined>(data, AdminRequest.PeerJoin(link)).peer
            println("Emparejado con ${printable(peer.name)} (huella ${peer.fingerprint}). Desde ahora comparten dispositivos y posiciones cada minuto.")
        }
        "remove" -> {
            val peer = arguments.positional.getOrNull(1) ?: throw UsageException("Indica el servidor: su nombre exacto o su huella")
            expect<AdminReply.Peers>(data, AdminRequest.PeerRemove(peer))
            println("Ya no se comparte nada con «$peer». Lo que llegó de él se queda; quítalo también en el otro servidor.")
        }
        else -> throw UsageException("Orden desconocida «peers $action». Usa: peers, peers invite, peers join o peers remove")
    }
}

private fun peerInvite(data: DataDirectory, png: Path?) {
    var joined = false
    AdminClient(data.adminSocket).exchange(AdminRequest.PeerInvite, timeout = Duration.ofMinutes(11)) { reply ->
        when (reply) {
            is AdminReply.Invite -> {
                val matrix = qrMatrix(reply.uri)
                println()
                print(terminalQr(matrix))
                println()
                png?.let {
                    writeQrPng(matrix, it)
                    println("Código QR guardado en $it")
                }
                println("En el otro servidor: en un teléfono, Servidor > Servidores emparejados > Unirme, y escanea el código;")
                println("en Linux, corre privatetracker-server peers join y pega este enlace cuando lo pida:")
                println("  ${reply.uri}")
                println()
                println("Huella de este servidor: ${reply.fingerprint}")
                println("Vale hasta las ${clock(Instant.ofEpochMilli(reply.expiresAtMillis))}. Esperando al otro servidor... (Ctrl+C cancela)")
            }
            is AdminReply.PeerJoined -> {
                joined = true
                println("Emparejado con ${printable(reply.peer.name)} (huella ${reply.peer.fingerprint}). Compara la huella en ese servidor.")
            }
            AdminReply.Expired -> println("La invitación venció sin que otro servidor la usara. Genera otra con: privatetracker-server peers invite")
            is AdminReply.Error -> fail(reply.message, EXIT_ERROR)
            else -> Unit
        }
    }
    if (!joined) exitProcess(EXIT_ERROR)
}

private fun printPeers(peers: List<PeerRow>) {
    if (peers.isEmpty()) {
        println("No hay servidores emparejados. Invita a otro con: privatetracker-server peers invite")
        return
    }
    val rows = peers.map {
        val last = it.lastExchangeAtMillis?.let { ms -> dateTime(Instant.ofEpochMilli(ms)) } ?: "nunca"
        listOf(printable(it.name), it.fingerprint, last, it.lastError?.let(::peerErrorLabel) ?: "bien", it.addresses.firstOrNull() ?: "-")
    }
    val header = listOf("SERVIDOR", "HUELLA", "ÚLTIMO INTERCAMBIO", "ESTADO", "DIRECCIÓN")
    val widths = header.indices.map { column -> (rows.map { it[column] } + header[column]).maxOf { it.length } }
    (listOf(header) + rows).forEach { row -> println(row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()) }
}

private fun peerErrorLabel(code: String) = when (code) {
    "PEER_UNKNOWN" -> "ese servidor ya no está emparejado con este"
    "SERVER_IDENTITY_MISMATCH" -> "en su dirección responde otro servidor"
    "NETWORK_UNREACHABLE", "NETWORK_TIMEOUT" -> "no se le alcanza"
    else -> "falló ($code)"
}

/** Asked on a terminal; without one, the first line of the standard input. */
private fun readInviteLink(): String {
    val console = System.console()
    val line = if (console != null) console.readLine("Pega el enlace de la invitación (privatetracker://peer?...): ") else readlnOrNull()
    return line?.trim()?.takeIf { it.isNotEmpty() }
        ?: fail("Sin terminal, el enlace se lee de la entrada estándar: printf '%s\n' '<enlace>' | privatetracker-server peers join", EXIT_USAGE)
}

/** `users`, `users add|passwd|remove <nombre>`: the web app's accounts, through the running server. */
private fun users(arguments: Arguments, data: DataDirectory) {
    val action = arguments.positional.firstOrNull() ?: "list"
    fun name(): String = arguments.positional.getOrNull(1) ?: throw UsageException("Indica el nombre de la cuenta: privatetracker-server users $action <nombre>")
    when (action) {
        "list" -> {
            val users = expect<AdminReply.Users>(data, AdminRequest.Users).users
            if (users.isEmpty()) println("Todavía no hay cuentas. Crea una con: privatetracker-server users add <nombre>")
            users.forEach { println("${it.name}  (creada el ${dateTime(Instant.ofEpochMilli(it.createdAtMillis))})") }
        }
        "add" -> {
            val name = name()
            expect<AdminReply.Users>(data, AdminRequest.AddUser(name, readNewPassword()))
            println("Cuenta «${name.lowercase()}» creada. Ya puede entrar en la app web.")
        }
        "passwd" -> {
            val name = name()
            expect<AdminReply.Users>(data, AdminRequest.SetPassword(name, readNewPassword()))
            println("Contraseña cambiada; las sesiones abiertas de «${name.lowercase()}» se cerraron.")
        }
        "remove" -> {
            val name = name()
            expect<AdminReply.Users>(data, AdminRequest.RemoveUser(name))
            println("Cuenta «${name.lowercase()}» borrada; sus sesiones se cerraron.")
        }
        else -> throw UsageException("Orden desconocida «users $action». Usa: users, users add, users passwd o users remove")
    }
}

/**
 * Asked twice on a terminal, without echo. Without one (a script, `docker exec` without -t), the first
 * line of the standard input, so it never shows up in the process list or the shell's history.
 */
private fun readNewPassword(): String {
    val console = System.console()
    if (console != null) {
        val first = console.readPassword("Contraseña nueva (15 caracteres o más; una frase sirve): ") ?: fail("Cancelado", EXIT_USAGE)
        val second = console.readPassword("Repítela: ") ?: fail("Cancelado", EXIT_USAGE)
        if (!first.contentEquals(second)) fail("Las dos contraseñas no coinciden.", EXIT_USAGE)
        return String(first)
    }
    return readlnOrNull()?.takeIf { it.isNotEmpty() }
        ?: fail("Sin terminal, la contraseña se lee de la entrada estándar: printf '%s\n' \"…\" | privatetracker-server users add <nombre>", EXIT_USAGE)
}

private fun DeviceRow.describe() = "${printable(name)} (${id.take(8)}, huella ${fingerprint ?: "sin clave"})"

private fun statusLabel(status: String) = when (status) {
    "ONLINE" -> "En línea"
    "STALE" -> "Inactivo"
    else -> "Sin conexión"
}

private fun approvalLabel(approval: String) = when (approval) {
    "APPROVED" -> "Aprobado"
    "REJECTED" -> "Rechazado"
    else -> "Pendiente"
}

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun dateTime(instant: Instant) = DATE_TIME.format(instant.atZone(ZoneId.systemDefault()))
private fun clock(instant: Instant) = CLOCK.format(instant.atZone(ZoneId.systemDefault()))
