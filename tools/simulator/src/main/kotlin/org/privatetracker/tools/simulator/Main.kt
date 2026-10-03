package org.privatetracker.tools.simulator

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.privatetracker.core.domain.validation.TrackerConfigValidator
import org.privatetracker.core.network.KtorServerGateway
import org.privatetracker.core.network.createProtocolHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private val USAGE = """
    Uso:
      simulator run --server URL [opciones]
      simulator verify --run CARPETA (--pull | --db ARCHIVO) [opciones]

    run: Trackers simulados envían posiciones al servidor y anotan cada una en CARPETA/ledger.csv.
      --server URL         URL del servidor, como en los ajustes del Tracker (obligatoria)
      --trackers N         Trackers simulados (10)
      --interval D         una posición por Tracker cada D (60s)
      --duration D         duración de la corrida (24h)
      --batch-size N       posiciones por envío, 1 a 100 (50)
      --max-queue N        tamaño máximo de la cola de cada Tracker (10000)
      --drop P             probabilidad de que un envío no salga, de 0 a 0.9 (0)
      --lost-ack P         probabilidad de perder la respuesta de un envío, de 0 a 0.9 (0)
      --drain-timeout D    tiempo para vaciar las colas al terminar (10m)
      --report-every D     cada cuánto se imprime el avance (5m)
      --center LAT,LON     centro de las rutas simuladas (19.4326,-99.1332)
      --out CARPETA        dónde guardar ledger.csv y summary.txt (simulator-runs/<fecha-hora>)

    verify: compara el ledger de una corrida con server.db.
      --run CARPETA        carpeta de la corrida, la que contiene ledger.csv (obligatoria)
      --pull               copia server.db del teléfono con adb (solo builds debug)
      --db ARCHIVO         usa una copia de server.db ya extraída
      --serial ID          teléfono de adb, si hay más de uno conectado
      --package NOMBRE     paquete de la app (org.privatetracker)
      --adb RUTA           ejecutable de adb (se busca en ANDROID_HOME, ~/Android/Sdk y el PATH)

    Duraciones: 500ms, 30s, 90m, 24h, 1h30m.
""".trimIndent()

fun main(args: Array<String>) {
    val code = try {
        when (args.firstOrNull()) {
            "run" -> runCommand(CommandLine(args.drop(1)))
            "verify" -> verifyCommand(CommandLine(args.drop(1), switches = setOf("--pull")))
            null, "help", "--help", "-h" -> {
                println(USAGE)
                0
            }
            else -> throw UsageException("Comando desconocido: ${args[0]}")
        }
    } catch (e: UsageException) {
        System.err.println("Error: ${e.message}")
        System.err.println("Ejecuta `simulator help` para ver las opciones.")
        2
    } catch (e: SimulationAborted) {
        System.err.println("No se inició la corrida: ${e.message}")
        1
    }
    exitProcess(code)
}

private fun runCommand(cli: CommandLine): Int {
    val serverUrl = cli.required("--server")
    TrackerConfigValidator.validateServerUrl(serverUrl)?.let {
        throw UsageException("URL de servidor inválida: $serverUrl (ejemplo: http://192.168.1.50:8787)")
    }
    val options = SimulationOptions(
        serverUrl = TrackerConfigValidator.normalizeServerUrl(serverUrl),
        trackers = cli.int("--trackers", 10, 1..1_000),
        interval = cli.duration("--interval", "60s"),
        duration = cli.duration("--duration", "24h"),
        batchSize = cli.int("--batch-size", 50, TrackerConfigValidator.BATCH_SIZE),
        maxQueueSize = cli.int("--max-queue", 10_000, TrackerConfigValidator.MAX_QUEUE_SIZE),
        faults = FaultPlan(
            dropRate = cli.probability("--drop"),
            lostAckRate = cli.probability("--lost-ack"),
        ),
        drainTimeout = cli.duration("--drain-timeout", "10m"),
        reportEvery = cli.duration("--report-every", "5m"),
        center = cli.string("--center")?.let(::parseCenter) ?: GeoPoint(19.4326, -99.1332),
        outDir = Path.of(cli.string("--out") ?: "simulator-runs/" + LocalDateTime.now().format(RUN_NAME)),
    )
    cli.rejectUnknown()

    val clients = mutableListOf<HttpClient>()
    val stop = CompletableDeferred<Unit>()
    val finished = CountDownLatch(1)
    val exitCode = AtomicInteger(1)
    // Ctrl+C ends the run early. The hook keeps the JVM alive while the outboxes drain, then exits
    // with the run's own code instead of the signal's.
    val hook = Thread {
        stop.complete(Unit)
        if (finished.await(options.drainTimeout.plusMinutes(1).toMillis(), TimeUnit.MILLISECONDS)) {
            Runtime.getRuntime().halt(exitCode.get())
        }
    }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        val result = runBlocking {
            Simulation(
                options = options,
                gatewayFactory = { keys ->
                    val client = createProtocolHttpClient(OkHttp.create(), USER_AGENT)
                    synchronized(clients) { clients += client }
                    KtorServerGateway(client, keys)
                },
            ).run(stop)
        }
        exitCode.set(if (result.trackerSideClean) 0 else 1)
        return exitCode.get()
    } finally {
        synchronized(clients) { clients.forEach { it.close() } }
        System.out.flush()
        finished.countDown()
        // Throws once the JVM is already shutting down, which is exactly when the hook must stay.
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private fun verifyCommand(cli: CommandLine): Int {
    val runDir = Path.of(cli.required("--run"))
    val pull = cli.flag("--pull")
    val explicitDb = cli.string("--db")?.let(Path::of)
    val serial = cli.string("--serial")
    val packageName = cli.string("--package") ?: AdbDatabasePuller.DEFAULT_PACKAGE
    val adb = AdbDatabasePuller.findAdb(cli.string("--adb"))
    cli.rejectUnknown()
    if (pull == (explicitDb != null)) throw UsageException("Indica --pull o --db, uno de los dos")

    val ledger = Ledger.read(runDir.resolve(Ledger.FILE_NAME))
    val db = explicitDb ?: run {
        println("Copiando server.db de $packageName con $adb… (detén el servidor en la app antes, para que la copia sea consistente)")
        AdbDatabasePuller(adb, serial, packageName).pull(runDir.resolve("server-db"))
    }
    println("Base: $db")
    val report = Verifier.verify(ledger, SqliteServerSnapshot(db))
    val text = Verifier.format(report)
    println()
    println(text)
    Files.writeString(runDir.resolve("verification.txt"), text + "\n")
    return if (report.passed) 0 else 1
}

private val RUN_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private val USER_AGENT = "PrivateTracker-Simulator/$SIMULATOR_VERSION (JVM ${System.getProperty("java.version")})"

private fun parseCenter(raw: String): GeoPoint {
    val parts = raw.split(",").map { it.trim().toDoubleOrNull() }
    val latitude = parts.getOrNull(0)
    val longitude = parts.getOrNull(1)
    if (parts.size != 2 || latitude == null || longitude == null || latitude !in -85.0..85.0 || longitude !in -180.0..180.0) {
        throw UsageException("Centro inválido: $raw (ejemplo: 19.4326,-99.1332)")
    }
    return GeoPoint(latitude, longitude)
}

/** `--name value` options plus bare switches; anything left unread is reported as unknown. */
internal class CommandLine(args: List<String>, switches: Set<String> = emptySet()) {
    private val values = linkedMapOf<String, String>()
    private val present = mutableSetOf<String>()
    private val read = mutableSetOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val name = args[i]
            if (!name.startsWith("--")) throw UsageException("Argumento inesperado: $name")
            if (name in switches) {
                present += name
                i++
            } else {
                values[name] = args.getOrNull(i + 1) ?: throw UsageException("Falta el valor de $name")
                i += 2
            }
        }
    }

    fun flag(name: String): Boolean = (name in present).also { read += name }

    fun string(name: String): String? = values[name].also { read += name }

    fun required(name: String): String = string(name) ?: throw UsageException("Falta $name")

    fun int(name: String, default: Int, range: IntRange): Int {
        val raw = string(name) ?: return default
        return raw.toIntOrNull()?.takeIf { it in range }
            ?: throw UsageException("$name debe ser un entero entre ${range.first} y ${range.last}: $raw")
    }

    fun probability(name: String): Double {
        val raw = string(name) ?: return 0.0
        return raw.toDoubleOrNull()?.takeIf { it in 0.0..0.9 }
            ?: throw UsageException("$name debe ser un número entre 0 y 0.9: $raw")
    }

    fun duration(name: String, default: String) = parseDuration(string(name) ?: default)

    fun rejectUnknown() {
        val unknown = (values.keys + present) - read
        if (unknown.isNotEmpty()) throw UsageException("Opción desconocida: ${unknown.joinToString()}")
    }
}
