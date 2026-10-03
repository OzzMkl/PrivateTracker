package org.privatetracker.core.common.time

import java.time.Instant

/** Source of the current time, injected so tests control it. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val System: Clock = Clock { Instant.now() }
    }
}
