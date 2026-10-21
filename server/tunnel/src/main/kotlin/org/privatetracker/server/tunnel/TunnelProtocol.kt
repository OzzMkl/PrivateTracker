package org.privatetracker.server.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * What a server and its relay say to each other, inside TLS with the server's identity key as client
 * certificate: one JSON line per message. A server keeps one control connection open ([Hello], then
 * [Ping]s); for each tracker that arrives, the relay asks for a data connection ([Open]), and the
 * server dials one more, names the stream ([Data]) and from then on that connection carries the
 * tracker's own TLS bytes, which neither the relay nor this protocol opens.
 */
@Serializable
sealed interface TunnelMessage {
    /** First line of a control connection. [web]: the server also takes browsers for its web app. */
    @Serializable @SerialName("hello")
    data class Hello(val version: Int, val web: Boolean) : TunnelMessage

    @Serializable @SerialName("ready")
    data class Ready(val version: Int) : TunnelMessage

    /** The relay will not carry this server; see [RefusalReason]. The connection closes after it. */
    @Serializable @SerialName("refused")
    data class Refused(val reason: String) : TunnelMessage

    /**
     * A tracker or browser arrived for [target]: dial a data connection for [stream]. [from] is the
     * visitor's address as the relay saw it, which the server's limits count against.
     */
    @Serializable @SerialName("open")
    data class Open(val stream: String, val target: TunnelTarget, val from: String? = null) : TunnelMessage

    /** First and only line of a data connection; raw bytes follow. */
    @Serializable @SerialName("data")
    data class Data(val stream: String) : TunnelMessage

    @Serializable @SerialName("ping")
    data object Ping : TunnelMessage

    @Serializable @SerialName("pong")
    data object Pong : TunnelMessage

    /** Asked by the relay's own health check, without a certificate. */
    @Serializable @SerialName("health")
    data object Health : TunnelMessage

    @Serializable @SerialName("ok")
    data object Ok : TunnelMessage
}

/** What a tunneled connection is for: the trackers' API, or the web app. */
@Serializable
enum class TunnelTarget {
    @SerialName("api") API,
    @SerialName("web") WEB,
}

object RefusalReason {
    const val NOT_ALLOWED = "not-allowed"
    const val VERSION = "version"
}

object Tunnel {
    const val VERSION = 1

    /** No message of this protocol comes near it. */
    private const val MAX_LINE_BYTES = 1024

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun write(output: OutputStream, message: TunnelMessage) {
        output.write((json.encodeToString(TunnelMessage.serializer(), message) + "\n").encodeToByteArray())
        output.flush()
    }

    /**
     * The next message, or null at the end of the stream. Reads byte by byte, never past the line's
     * end: on a data connection the bytes after it belong to someone else's TLS.
     */
    fun read(input: InputStream): TunnelMessage? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.size() == 0) null else throw IOException("Line cut short")
            if (byte == '\n'.code) break
            if (line.size() >= MAX_LINE_BYTES) throw IOException("Line too long")
            line.write(byte)
        }
        return try {
            json.decodeFromString(TunnelMessage.serializer(), line.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IOException("Not a tunnel message", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a tunnel message", e)
        }
    }
}

/** The names a relay answers to, and what each one leads to. */
object RelayNames {
    private val LABEL = Regex("^[0-9a-f]{16}$")

    /** A server's label and the target of [serverName] under [domain], or null for any other name. */
    fun parse(serverName: String, domain: String): Pair<String, TunnelTarget>? {
        val prefix = serverName.lowercase().removeSuffix(".$domain").takeIf { it != serverName.lowercase() } ?: return null
        return when {
            LABEL.matches(prefix) -> prefix to TunnelTarget.API
            prefix.startsWith("web-") && LABEL.matches(prefix.removePrefix("web-")) -> prefix.removePrefix("web-") to TunnelTarget.WEB
            else -> null
        }
    }
}
