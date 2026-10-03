package org.privatetracker.core.domain.network

import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.NetworkAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressesTest {
    @Test
    fun `private, link-local and mdns hosts need local network access`() {
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.3.4", "[fd12::1]", "fe80::1%wlan0", "nas.local")
            .forEach { assertTrue(needsLocalNetworkAccess(it), it) }
    }

    @Test
    fun `loopback, public, cgnat and plain names do not`() {
        listOf("127.0.0.1", "8.8.8.8", "172.32.0.1", "100.101.102.103", "localhost", "tracker.example.org", "999.1.1.1")
            .forEach { assertFalse(needsLocalNetworkAccess(it), it) }
    }

    @Test
    fun `addresses are classified by interface first, then by range`() {
        assertEquals(AddressKind.LOOPBACK, classify(NetworkAddress("lo", "127.0.0.1")))
        assertEquals(AddressKind.VPN, classify(NetworkAddress("tun0", "100.64.0.7")))
        assertEquals(AddressKind.VPN, classify(NetworkAddress("wg0", "10.8.0.2")))
        assertEquals(AddressKind.LAN, classify(NetworkAddress("wlan0", "192.168.1.20")))
        assertEquals(AddressKind.OTHER, classify(NetworkAddress("rmnet_data0", "100.72.1.9")))
    }
}
