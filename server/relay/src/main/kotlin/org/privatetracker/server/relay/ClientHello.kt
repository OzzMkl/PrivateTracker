package org.privatetracker.server.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The start of a TLS connection as the relay reads it: every byte of the ClientHello, to hand on
 * unchanged, and the server name (SNI) it asks for, which is all the relay ever learns of what the
 * connection is for.
 */
class ClientHello(val bytes: ByteArray, val serverName: String?) {
    companion object {
        private const val HANDSHAKE = 22
        private const val CLIENT_HELLO = 1
        private const val SERVER_NAME = 0
        private const val HOST_NAME = 0
        private const val MAX_RECORD = 16_384 + 256

        /** A ClientHello split over more records than browsers ever send is not worth reading. */
        private const val MAX_BYTES = 32 * 1024

        /** Throws IOException when the connection does not start with a TLS ClientHello. */
        fun read(input: InputStream): ClientHello {
            val data = DataInputStream(input)
            val raw = ByteArrayOutputStream()
            val handshake = ByteArrayOutputStream()
            var expected = -1
            while (expected < 0 || handshake.size() < expected) {
                val type = data.readUnsignedByte()
                val version = data.readUnsignedShort()
                val length = data.readUnsignedShort()
                if (type != HANDSHAKE || version shr 8 != 3 || length == 0 || length > MAX_RECORD) throw IOException("Not a TLS handshake")
                val fragment = ByteArray(length).also(data::readFully)
                raw.write(type)
                raw.write(version shr 8)
                raw.write(version and 0xff)
                raw.write(length shr 8)
                raw.write(length and 0xff)
                raw.write(fragment)
                handshake.write(fragment)
                if (raw.size() > MAX_BYTES) throw IOException("ClientHello too long")
                if (expected < 0 && handshake.size() >= 4) {
                    val header = handshake.toByteArray()
                    if (header[0].toInt() != CLIENT_HELLO) throw IOException("Not a ClientHello")
                    expected = 4 + ((header[1].toInt() and 0xff) shl 16 or ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff))
                }
            }
            val body = handshake.toByteArray().copyOfRange(4, expected)
            return ClientHello(raw.toByteArray(), serverName(body))
        }

        private fun serverName(body: ByteArray): String? = try {
            val reader = Reader(body)
            reader.skip(2 + 32)
            reader.skip(reader.u8())
            reader.skip(reader.u16())
            reader.skip(reader.u8())
            if (reader.remaining == 0) {
                null
            } else {
                val end = reader.u16() + reader.position
                var name: String? = null
                while (reader.position < end && name == null) {
                    val type = reader.u16()
                    val length = reader.u16()
                    if (type == SERVER_NAME) name = hostName(Reader(reader.bytes(length))) else reader.skip(length)
                }
                name
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IOException("Malformed ClientHello", e)
        }

        private fun hostName(extension: Reader): String? {
            val end = extension.u16() + extension.position
            while (extension.position < end) {
                val type = extension.u8()
                val name = extension.bytes(extension.u16())
                if (type == HOST_NAME) return name.decodeToString().lowercase().takeIf { it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' } }
            }
            return null
        }
    }

    private class Reader(private val data: ByteArray) {
        var position = 0
            private set
        val remaining: Int get() = data.size - position

        fun u8(): Int = (data[position++].toInt() and 0xff)
        fun u16(): Int = (u8() shl 8) or u8()

        fun skip(count: Int) {
            if (count > remaining) throw IndexOutOfBoundsException()
            position += count
        }

        fun bytes(count: Int): ByteArray {
            if (count > remaining) throw IndexOutOfBoundsException()
            return data.copyOfRange(position, position + count).also { position += count }
        }
    }
}
