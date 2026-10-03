package org.privatetracker.tools.simulator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.ConnectionCheck
import org.privatetracker.core.domain.model.DeviceId
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.port.DeviceKeys
import org.privatetracker.core.domain.port.ServerGateway
import org.privatetracker.core.domain.usecase.tracker.TestServerConnection
import org.privatetracker.core.protocol.crypto.InMemoryDeviceKeys
import java.io.PrintStream
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** The run cannot start: the server is unreachable, speaks another protocol, or its clock is too far off. */
class SimulationAborted(message: String) : Exception(message)

/** Totals over every tracker of a run. */
data class SimulationResult(
    val generated: Long,
    val acknowledged: Long,
    val accepted: Long,
    val duplicates: Long,
    val rejected: Long,
    val dropped: Long,
    val pending: Long,
    val failures: Map<String, Long>,
    val injectedDrops: Long,
    val injectedLostAcks: Long,
    val latenciesMs: List<Long>,
    val elapsed: Duration,
    val stoppedEarly: Boolean,
) {
    /** Every generated position left its outbox because the server acknowledged it. */
    val trackerSideClean: Boolean get() = rejected == 0L && dropped == 0L && pending == 0L
}

/**
 * Runs [SimulationOptions.trackers] simulated phones against one server for [SimulationOptions.duration],
 * or until [stop] completes, then lets them drain their outboxes and reports what the server acknowledged.
 * [gatewayFactory] is called once per tracker, so each phone has its own HTTP connections; it signs
 * with the keys it is given. New devices wait, queuing, until the server's owner approves them.
 */
class Simulation(
    private val options: SimulationOptions,
    private val gatewayFactory: (DeviceKeys) -> ServerGateway,
    private val out: PrintStream = System.out,
    private val clock: Clock = Clock.System,
) {
    suspend fun run(stop: Deferred<Unit> = CompletableDeferred()): SimulationResult = coroutineScope {
        val check = checkServer()
        val ledger = Ledger.create(options.outDir)
        val keys = InMemoryDeviceKeys()
        val trackers = (1..options.trackers).map { n ->
            val id = DeviceId.of(UUID.randomUUID().toString())
            val name = "Simulador %02d".format(n)
            ledger.device(id, name)
            SimulatedTracker(id, name, options, gatewayFactory(keys), keys, ledger, Random(Random.nextLong()), clock)
        }
        printStart(check)
        out.println("Aprueba estos dispositivos en la app (Dispositivos); mientras tanto sus posiciones esperan en la cola:")
        trackers.forEach { out.println("  ${it.name}  ${keyFingerprint(keys.publicKey(it.deviceId))}") }
        out.println()

        val started = TimeSource.Monotonic.markNow()
        val running = launch { trackers.forEach { tracker -> launch { tracker.run() } } }
        val progress = launch {
            while (true) {
                delay(options.reportEvery.toMillis())
                out.println("[${formatDuration(started.elapsedNow().toJavaDuration())}] ${progressLine(trackers)}")
            }
        }
        val stoppedEarly = withTimeoutOrNull(options.duration.toMillis()) { stop.await() } != null
        running.cancelAndJoin()
        val ranFor = started.elapsedNow().toJavaDuration()

        out.println()
        out.println(if (stoppedEarly) "Detenida a mano. Vaciando las colas…" else "Tiempo cumplido. Vaciando las colas…")
        val drained = trackers.map { async { it.drain(options.drainTimeout) } }.awaitAll()
        progress.cancelAndJoin()
        ledger.finished(RunEnd(ranFor, stoppedEarly, drained = drained.all { it }))
        ledger.close()
        if (!drained.all { it }) {
            out.println("Algunas colas no se vaciaron en ${formatDuration(options.drainTimeout)}: ¿el servidor sigue encendido y alcanzable?")
        }

        val result = totals(trackers, ranFor, stoppedEarly)
        val summary = summary(check, result)
        out.println()
        out.println(summary)
        Files.writeString(options.outDir.resolve(SUMMARY_FILE), summary + "\n")
        result
    }

    private suspend fun checkServer(): ConnectionCheck {
        val check = when (val outcome = TestServerConnection(gatewayFactory(InMemoryDeviceKeys()), clock)(options.serverUrl)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw SimulationAborted("No se pudo conectar con ${options.serverUrl}: ${outcome.error.code}")
        }
        if (!check.compatible) {
            throw SimulationAborted("El servidor habla el protocolo ${check.server.protocolVersion}; el simulador, el 1")
        }
        // The server rejects fixes stamped more than 5 min ahead of its own clock.
        if (check.clockOffset < MAX_TRACKER_AHEAD.negated()) {
            throw SimulationAborted(
                "El reloj de esta computadora va ${formatDuration(check.clockOffset.negated())} adelantado respecto al servidor; " +
                    "el servidor rechazaría las posiciones. Sincroniza la hora de ambos equipos.",
            )
        }
        return check
    }

    private fun printStart(check: ConnectionCheck) {
        out.println("PrivateTracker simulator $SIMULATOR_VERSION")
        out.println(
            "Servidor: ${options.serverUrl} — ${check.server.name} ${check.server.version}, " +
                "latencia ${check.latency.toMillis()} ms, desfase de reloj ${formatOffset(check.clockOffset)}",
        )
        if (check.clockOffset.abs() > CLOCK_WARNING) {
            out.println("Aviso: los relojes difieren más de ${CLOCK_WARNING.seconds} s; conviene sincronizarlos.")
        }
        out.println(
            "${options.trackers} Trackers, una posición cada ${formatDuration(options.interval)} " +
                "durante ${formatDuration(options.duration)}. Ctrl+C termina antes y vacía las colas.",
        )
        if (options.faults != FaultPlan()) {
            out.println("Fallas inyectadas: ${percent(options.faults.dropRate)} de envíos sin salir, ${percent(options.faults.lostAckRate)} sin acuse.")
        }
        out.println("Ledger: ${options.outDir.resolve(Ledger.FILE_NAME)}")
        out.println()
    }

    private suspend fun progressLine(trackers: List<SimulatedTracker>): String {
        val generated = trackers.sumOf { it.stats.generated.get() }
        val acknowledged = trackers.sumOf { it.stats.acknowledgedCount.toLong() }
        val queued = trackers.sumOf { it.queued().toLong() }
        val failures = trackers.flatMap { it.stats.failuresByCode().entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val waiting = trackers.count { it.stats.waitingForApproval }
        val approval = if (waiting > 0) " · sin aprobar $waiting" else ""
        return "generadas $generated · confirmadas $acknowledged · en cola $queued$approval · fallos ${formatFailures(failures)}"
    }

    private suspend fun totals(trackers: List<SimulatedTracker>, elapsed: Duration, stoppedEarly: Boolean): SimulationResult {
        val stats = trackers.map { it.stats }
        return SimulationResult(
            generated = stats.sumOf { it.generated.get() },
            acknowledged = stats.sumOf { it.acknowledgedCount.toLong() },
            accepted = stats.sumOf { it.accepted.get() },
            duplicates = stats.sumOf { it.duplicates.get() },
            rejected = stats.sumOf { it.rejected.get() },
            dropped = stats.sumOf { it.dropped.get() },
            pending = trackers.sumOf { it.queued().toLong() },
            failures = stats.flatMap { it.failuresByCode().entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
            injectedDrops = stats.sumOf { it.injectedDrops.get() },
            injectedLostAcks = stats.sumOf { it.injectedLostAcks.get() },
            latenciesMs = stats.flatMap { it.latencies() },
            elapsed = elapsed,
            stoppedEarly = stoppedEarly,
        )
    }

    private fun summary(check: ConnectionCheck, result: SimulationResult): String = buildString {
        appendLine("Resumen de la corrida")
        appendLine("  Servidor:                 ${options.serverUrl} (${check.server.name} ${check.server.version})")
        appendLine("  Trackers:                 ${options.trackers}, una posición cada ${formatDuration(options.interval)}")
        appendLine("  Duración:                 ${formatDuration(result.elapsed)}${if (result.stoppedEarly) " (detenida a mano)" else ""}")
        appendLine("  Generadas:                ${result.generated}")
        appendLine("  Confirmadas:              ${result.acknowledged} (aceptadas ${result.accepted}, duplicadas ${result.duplicates})")
        appendLine("  Rechazadas:               ${result.rejected}")
        appendLine("  Descartadas (cola llena): ${result.dropped}")
        appendLine("  Pendientes al terminar:   ${result.pending}")
        appendLine("  Fallos de envío:          ${formatFailures(result.failures)}")
        if (options.faults != FaultPlan()) {
            appendLine("  Fallas inyectadas:        ${result.injectedDrops} envíos sin salir, ${result.injectedLostAcks} sin acuse")
        }
        appendLine("  Latencia de envío:        ${formatLatencies(result.latenciesMs)}")
        appendLine("  Ledger:                   ${options.outDir.resolve(Ledger.FILE_NAME)}")
        appendLine()
        if (result.trackerSideClean) {
            appendLine("Lado del Tracker: sin pérdidas. Falta comparar con server.db:")
            append("  simulator verify --run ${options.outDir} --pull")
        } else {
            append("Lado del Tracker: hay posiciones rechazadas, descartadas o pendientes; la corrida no cumple el criterio.")
        }
    }

    private companion object {
        const val SUMMARY_FILE = "summary.txt"
        val MAX_TRACKER_AHEAD: Duration = Duration.ofMinutes(4)
        val CLOCK_WARNING: Duration = Duration.ofSeconds(30)

        fun percent(probability: Double) = "%.0f %%".format(probability * 100)

        fun formatOffset(offset: Duration) = "%+.1f s".format(offset.toMillis() / 1_000.0)

        fun formatFailures(failures: Map<String, Long>): String =
            if (failures.isEmpty()) {
                "0"
            } else {
                "${failures.values.sum()} (" + failures.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" } + ")"
            }

        fun formatLatencies(latencies: List<Long>): String {
            if (latencies.isEmpty()) return "sin datos"
            val sorted = latencies.sorted()
            fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            return "p50 ${percentile(0.5)} ms · p95 ${percentile(0.95)} ms · máx ${sorted.last()} ms"
        }
    }
}
