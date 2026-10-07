package org.privatetracker.core.domain.network

import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.model.ServerAddress

private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
private val VPN_INTERFACE_PREFIXES = listOf("tun", "wg", "ppp", "ipsec", "tap", "tailscale")

/** The four octets of an IPv4 literal, or null when [host] is not one. */
private fun ipv4Octets(host: String): List<Int>? =
    IPV4.matchEntire(host)?.groupValues?.drop(1)?.map(String::toInt)?.takeIf { octets -> octets.all { it in 0..255 } }

private fun isPrivateIpv4(octets: List<Int>): Boolean {
    val (a, b) = octets
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

/** Unique local (fc00::/7) and link-local (fe80::/10) IPv6 addresses. */
private fun isLocalIpv6(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
    if (':' !in bare) return false
    val first = bare.substringBefore(':').toIntOrNull(16) ?: return false
    return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
}

/**
 * True when reaching [host] surely needs Android 17's local network permission: private and link-local
 * ranges, and mDNS `.local` names. 100.64.0.0/10 counts as local only over Wi-Fi, while VPNs such as
 * Tailscale use it too, so that range and other names are left to the connection test to diagnose.
 */
fun needsLocalNetworkAccess(host: String): Boolean {
    ipv4Octets(host)?.let { return isPrivateIpv4(it) }
    if (isLocalIpv6(host)) return true
    return host.lowercase().endsWith(".local")
}

fun classify(address: NetworkAddress): AddressKind {
    val octets = ipv4Octets(address.host)
    return when {
        address.interfaceName == "lo" || octets?.first() == 127 || address.host == "::1" -> AddressKind.LOOPBACK
        VPN_INTERFACE_PREFIXES.any { address.interfaceName.startsWith(it) } -> AddressKind.VPN
        octets != null && isPrivateIpv4(octets) -> AddressKind.LAN
        else -> AddressKind.OTHER
    }
}

/**
 * URLs under which trackers can reach a server on [port]: LAN first, then VPN, other and loopback.
 * A server bound to one address is reachable only there. Only IPv4 is listed, since an IPv6 URL
 * with brackets is hard to type on a phone.
 */
fun serverAddresses(addresses: List<NetworkAddress>, port: Int, bindAddress: String = ANY_ADDRESS): List<ServerAddress> {
    val listening = if (bindAddress == ANY_ADDRESS || bindAddress == "::") addresses else addresses.filter { it.host == bindAddress }
    return listening
        .filter { ipv4Octets(it.host) != null }
        .map { ServerAddress("https://${it.host}:$port", classify(it)) }
        .distinctBy { it.url }
        .sortedBy { it.kind.ordinal }
}

private const val ANY_ADDRESS = "0.0.0.0"

/** The URL a tracker on the server's own phone uses. */
fun loopbackUrl(port: Int): String = "https://127.0.0.1:$port"
