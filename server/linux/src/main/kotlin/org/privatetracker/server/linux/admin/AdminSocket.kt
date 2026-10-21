package org.privatetracker.server.linux.admin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.privatetracker.server.hostkeys.OwnerOnly
import org.privatetracker.server.linux.DataDirectory
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Lines of text over a socket channel. One thread may read while another writes: the channel allows
 * that, while the streams of java.nio.channels.Channels would make the writer wait for the reader.
 */
internal class LineChannel(private val channel: SocketChannel) : Closeable {
    private val buffer = ByteBuffer.allocate(8_192)
    private val pending = ByteArrayOutputStream()

    /** The next line without its newline; null once the other end closed. */
    fun readLine(): String? {
        while (true) {
            val bytes = pending.toByteArray()
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline >= 0) {
                pending.reset()
                pending.write(bytes, newline + 1, bytes.size - newline - 1)
                return bytes.decodeToString(0, newline)
            }
            if (bytes.size > MAX_LINE_CHARS * 4) throw IOException("Line too long")
            buffer.clear()
            if (channel.read(buffer) < 0) {
                pending.reset()
                return if (bytes.isEmpty()) null else bytes.decodeToString()
            }
            pending.write(buffer.array(), 0, buffer.position())
        }
    }

    fun writeLine(line: String) {
        val bytes = ByteBuffer.wrap((line + "\n").encodeToByteArray())
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    override fun close() = channel.close()
}

/** One command-line client, as the server's handler sees it. */
class AdminConnection internal constructor(private val lines: LineChannel, private val scope: CoroutineScope) {
    fun send(reply: AdminReply) = lines.writeLine(AdminJson.encodeToString(AdminReply.serializer(), reply))

    /** Completes when the client hangs up, as when its user presses Ctrl+C. */
    fun hangUp(): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        scope.launch {
            try {
                // Whatever it says now is ignored; only the end of the stream counts.
                while (true) lines.readLine() ?: break
            } catch (e: IOException) {
                // Closed under the reader: the conversation is over either way.
            }
            done.complete(Unit)
        }
        return done
    }
}

fun interface AdminHandler {
    suspend fun handle(request: AdminRequest, connection: AdminConnection)
}

class AlreadyRunningException(val socket: Path) : Exception("Another server answers on $socket")

/**
 * The server's end of the admin socket: a Unix socket in the data directory, so only the server's
 * user reaches it, also through `docker exec`. Each connection is served on its own coroutine.
 */
class AdminServer(private val socket: Path, private val handler: AdminHandler) {
    private val log = LoggerFactory.getLogger("Admin")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocketChannel? = null

    /** Throws [AlreadyRunningException] when another server uses the same data directory. */
    fun start() {
        if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            if (answers(socket)) throw AlreadyRunningException(socket)
            // Left behind by a server that did not stop cleanly.
            Files.delete(socket)
        }
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socket))
        Files.setPosixFilePermissions(socket, OwnerOnly.FILE)
        server = channel
        scope.launch { accept(channel) }
    }

    fun stop() {
        val bound = server ?: return
        server = null
        bound.close()
        scope.cancel()
        Files.deleteIfExists(socket)
    }

    /** Until [stop]: a failure such as running out of file descriptors pauses it, never ends it. */
    private suspend fun accept(channel: ServerSocketChannel) {
        while (true) {
            val client = try {
                channel.accept()
            } catch (e: ClosedChannelException) {
                return
            } catch (e: IOException) {
                log.warn("Admin socket cannot accept: {}", e.message)
                delay(1_000)
                continue
            }
            scope.launch { LineChannel(client).use { serve(it) } }
        }
    }

    private suspend fun serve(lines: LineChannel) {
        val connection = AdminConnection(lines, scope)
        val request = try {
            val line = lines.readLine() ?: return
            AdminJson.decodeFromString(AdminRequest.serializer(), line)
        } catch (e: SerializationException) {
            connection.send(AdminReply.Error("Petición no válida"))
            return
        } catch (e: IllegalArgumentException) {
            connection.send(AdminReply.Error("Petición no válida"))
            return
        }
        try {
            handler.handle(request, connection)
        } catch (e: IOException) {
            log.debug("Admin client left: {}", e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The command line gets an answer, and the server goes on serving the API.
            log.error("Admin request {} failed", request, e)
            runCatching { connection.send(AdminReply.Error("El servidor no pudo completar la orden: ${e.message ?: e.javaClass.simpleName}")) }
        }
    }

    companion object {
        fun answers(socket: Path): Boolean = try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).close()
            true
        } catch (e: IOException) {
            false
        }
    }
}

/** The command line's end: one request, then every reply line until the server closes. */
class AdminClient(private val socket: Path) {
    /**
     * Throws [ServerNotRunningException] when nothing answers on the socket. A server that accepts but
     * never answers is given up on after [timeout], so a stuck command cannot hang a script or a health check.
     */
    fun exchange(request: AdminRequest, timeout: Duration = Duration.ofSeconds(30), onReply: (AdminReply) -> Unit) {
        val channel = try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket))
        } catch (e: IOException) {
            throw ServerNotRunningException(socket)
        }
        val watchdog = thread(isDaemon = true, name = "admin-timeout") {
            try {
                Thread.sleep(timeout.toMillis())
                channel.close()
            } catch (e: InterruptedException) {
                // Answered in time.
            }
        }
        try {
            LineChannel(channel).use { lines ->
                lines.writeLine(AdminJson.encodeToString(AdminRequest.serializer(), request))
                while (true) {
                    val line = lines.readLine() ?: return
                    onReply(AdminJson.decodeFromString(AdminReply.serializer(), line))
                }
            }
        } catch (e: AsynchronousCloseException) {
            if (!watchdog.isAlive) throw ServerNotAnsweringException(socket)
            throw e
        } finally {
            watchdog.interrupt()
        }
    }

    fun single(request: AdminRequest): AdminReply {
        var reply: AdminReply? = null
        exchange(request) { if (reply == null) reply = it }
        return reply ?: AdminReply.Error("El servidor cerró la conexión sin responder")
    }
}

class ServerNotAnsweringException(val socket: Path) : Exception("The server on $socket did not answer in time")

class ServerNotRunningException(val socket: Path) : Exception("No server answers on $socket")
