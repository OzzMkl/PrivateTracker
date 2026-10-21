package org.privatetracker.server.tunnel

import java.util.concurrent.ConcurrentHashMap

/**
 * The connections a [RelayTunnel] has open to this server's own ports, each with the address of the
 * visitor it carries as the relay saw it. Those connections come from this machine, so the server
 * asks here before it takes one for its own: they are strangers from the internet, and their limits
 * count against the visitor, not against the tunnel all of them share.
 */
class TunnelVisitors {
    private val visitors = ConcurrentHashMap<Int, Pair<String, String>>()

    /** The visitor on the connection from [address]:[port] of this machine, or null when no tunnel opened it. */
    fun visitor(address: String, port: Int): String? = visitors[port]?.takeIf { it.first == address }?.second

    /** Before any byte of the visitor goes through, so no request on the connection is ever taken for local. */
    internal fun add(address: String, port: Int, visitor: String) {
        visitors[port] = address to visitor
    }

    internal fun remove(address: String, port: Int, visitor: String) {
        visitors.remove(port, address to visitor)
    }

    internal companion object {
        /** What a relay that names no visitor gets counted as: all of them together. */
        const val UNKNOWN = "relay"

        /** An address as text, nothing else: the relay is not trusted to send anything sensible. */
        fun sanitize(from: String?): String =
            from?.takeIf { it.length in 2..MAX_LENGTH && it.all { c -> c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c in ".:%" } } ?: UNKNOWN

        private const val MAX_LENGTH = 64
    }
}
