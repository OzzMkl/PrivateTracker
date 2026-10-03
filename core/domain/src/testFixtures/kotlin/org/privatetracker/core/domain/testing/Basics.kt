package org.privatetracker.core.domain.testing

import org.privatetracker.core.common.id.IdGenerator
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.port.BatteryLevelProvider
import org.privatetracker.core.domain.port.TransactionRunner
import java.time.Duration
import java.time.Instant

val T0: Instant = Instant.parse("2026-10-02T18:00:00Z")

class FakeClock(var current: Instant = T0) : Clock {
    override fun now(): Instant = current

    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

/** Predictable UUIDs: ...-000000000001, ...-000000000002, and so on. */
class SequentialIdGenerator : IdGenerator {
    private var next = 1L

    override fun newId(): String = "00000000-0000-4000-8000-%012d".format(next++)
}

/** In-memory fakes have nothing to roll back, so the block just runs. */
object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class FixedBattery(var level: Int? = 80) : BatteryLevelProvider {
    override fun currentLevelPct(): Int? = level
}

fun <T> Outcome<T>.successValue(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw AssertionError("Expected success but got $error")
}

fun Outcome<*>.failureError(): DomainError = when (this) {
    is Outcome.Success -> throw AssertionError("Expected failure but got $value")
    is Outcome.Failure -> error
}
