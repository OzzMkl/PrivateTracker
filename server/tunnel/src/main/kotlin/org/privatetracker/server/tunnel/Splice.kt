package org.privatetracker.server.tunnel

import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Copies bytes both ways between [a] and [b] until either side ends, then closes both: TLS sockets
 * cannot half-close, and the TLS inside has said goodbye by then anyway. A connection silent for
 * [idleMillis] in both directions is closed too; one quiet only one way (a browser listening to its
 * live stream) is not. Blocks the calling thread; the other direction gets a daemon thread of its own.
 */
fun splice(a: Socket, b: Socket, idleMillis: Int = IDLE_MILLIS) {
    a.soTimeout = idleMillis
    b.soTimeout = idleMillis
    val lastActive = AtomicLong(System.nanoTime())
    val idleNanos = idleMillis * 1_000_000L
    val other = thread(isDaemon = true, name = "tunnel-splice") { copy(b, a, lastActive, idleNanos) }
    copy(a, b, lastActive, idleNanos)
    other.join()
}

/** Ten minutes: trackers keep a connection for five after their last upload. */
const val IDLE_MILLIS = 10 * 60 * 1000

private fun copy(from: Socket, to: Socket, lastActive: AtomicLong, idleNanos: Long) {
    val buffer = ByteArray(16 * 1024)
    try {
        val input = from.getInputStream()
        val output = to.getOutputStream()
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (e: SocketTimeoutException) {
                // Quiet between records, so nothing half read is lost by reading on.
                if (System.nanoTime() - lastActive.get() < idleNanos) continue else break
            }
            if (read < 0) break
            lastActive.set(System.nanoTime())
            output.write(buffer, 0, read)
            output.flush()
        }
    } catch (e: IOException) {
        // Closed by the other direction, reset, or idle too long: the end either way.
    } finally {
        closeQuietly(from)
        closeQuietly(to)
    }
}

fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (e: IOException) {
        // Nothing to do.
    }
}
