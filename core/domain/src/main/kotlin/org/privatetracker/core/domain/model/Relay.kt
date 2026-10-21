package org.privatetracker.core.domain.model

/**
 * A relay a server behind NAT reaches out to, from 1.0 on, so trackers can reach it from anywhere. The
 * server keeps a tunnel open to the relay; trackers connect to a name of the server's own under the
 * relay's domain, and the relay passes their TLS through without opening it. TLS still ends at the
 * server, with the key trackers pinned, so the relay sees neither content nor paths: only which
 * server, when, and how much.
 */
data class RelayAddress(val host: String, val port: Int = DEFAULT_PORT) {
    override fun toString(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 443

        /** Long enough for every name under it: `web-` and a 16-digit label, a dot, the domain. */
        const val HOST_MAX_LENGTH = 200

        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /**
         * `relay.example.org` or `relay.example.org:8443`, case ignored. A domain name with at least
         * one dot, never an IP address: trackers find the server by a name under it, which an address
         * cannot have. Null when it is not that.
         */
        fun parse(raw: String): RelayAddress? {
            val text = raw.trim().lowercase().removePrefix("https://").removeSuffix("/")
            val host = text.substringBefore(':')
            val port = if (':' in text) text.substringAfter(':').toIntOrNull() ?: return null else DEFAULT_PORT
            if (port !in 1..65535 || host.length > HOST_MAX_LENGTH) return null
            val labels = host.split('.')
            if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
            // All digits in the last label: an IPv4 address, not a domain.
            if (labels.last().all(Char::isDigit)) return null
            return RelayAddress(host, port)
        }
    }
}

/** The label a server goes by at a relay: the fingerprint of its key, lower case, without dashes. */
fun relayLabel(serverFingerprint: String): String = serverFingerprint.replace("-", "").lowercase()

/** Where trackers reach [serverFingerprint]'s server through [relay]. */
fun relayTrackerUrl(relay: RelayAddress, serverFingerprint: String): String =
    "https://${relayLabel(serverFingerprint)}.${relay.host}${portSuffix(relay)}"

/** Where a browser reaches the server's web app through [relay], when the server offers it there. */
fun relayWebUrl(relay: RelayAddress, serverFingerprint: String): String =
    "https://web-${relayLabel(serverFingerprint)}.${relay.host}${portSuffix(relay)}"

private fun portSuffix(relay: RelayAddress) = if (relay.port == RelayAddress.DEFAULT_PORT) "" else ":${relay.port}"

/** Why a relay would not carry this server. */
enum class RelayRefusal {
    /** This server's fingerprint is not on the relay's list. */
    NOT_ALLOWED,

    /** The relay speaks another version of the tunnel protocol. */
    VERSION,
}

/** How the server's tunnel to its relay is doing. */
sealed interface RelayState {
    data object Off : RelayState
    data object Connecting : RelayState

    /** Trackers reach the server at [trackerUrl]; browsers at [webUrl] when the server offers its web app there. */
    data class Connected(val trackerUrl: String, val webUrl: String?) : RelayState

    data class Refused(val reason: RelayRefusal) : RelayState

    /** The relay showed a key other than the fingerprint configured: maybe not the relay at all. */
    data object KeyMismatch : RelayState

    /** No answer, or the connection broke; the server keeps trying. */
    data class Unreachable(val detail: String) : RelayState
}

/**
 * The addresses a server hands to trackers, in the order they try them: its own addresses first, then
 * the relay's name for it, which works from anywhere but costs a detour.
 */
fun advertisedAddresses(local: List<String>, config: ServerConfig, serverFingerprint: String?): List<String> {
    val relay = config.relay
    val viaRelay = if (relay != null && serverFingerprint != null) listOf(relayTrackerUrl(relay, serverFingerprint)) else emptyList()
    // The relay keeps its place however many addresses there are: it is the one that works from anywhere.
    return local.distinct().take(MAX_ADVERTISED_ADDRESSES - viaRelay.size) + viaRelay
}

/** As many as a QR code holds comfortably, and as a tracker keeps. */
const val MAX_ADVERTISED_ADDRESSES = 8
