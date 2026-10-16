package org.privatetracker.server.linux.platform

import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.network.serverAddresses
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path

// Interfaces a host creates for containers and VMs: phones never reach the server through them.
private val BRIDGE_PREFIXES = listOf("docker", "br-", "veth", "virbr", "cni", "flannel", "lxc", "podman")

/** The host's IPv4 addresses that are up, as a phone server lists its own; empty when the OS will not say. */
fun hostAddresses(): List<NetworkAddress> = try {
    NetworkInterface.networkInterfaces().toList()
        .filter { it.isUp && BRIDGE_PREFIXES.none(it.name::startsWith) }
        .flatMap { nif -> nif.inetAddresses().toList().filterIsInstance<Inet4Address>().map { NetworkAddress(nif.name, it.hostAddress) } }
} catch (e: SocketException) {
    emptyList()
}

/**
 * True inside a container, where the addresses the server sees are the container's own: phones cannot
 * use them, so invites need the public URLs the owner configures.
 */
fun runsInContainer(): Boolean = Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))

/**
 * The URLs a pairing invite carries, in the order trackers try them: the configured public URLs, then,
 * outside a container, the host's LAN and VPN addresses.
 */
fun inviteUrls(publicUrls: List<String>, port: Int, bindAddress: String, inContainer: Boolean, addresses: List<NetworkAddress>): List<String> {
    val detected = if (inContainer) {
        emptyList()
    } else {
        serverAddresses(addresses, port, bindAddress).filter { it.kind == AddressKind.LAN || it.kind == AddressKind.VPN }.map { it.url }
    }
    return (publicUrls + detected).distinct()
}
