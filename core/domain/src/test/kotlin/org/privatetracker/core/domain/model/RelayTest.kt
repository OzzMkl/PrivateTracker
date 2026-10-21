package org.privatetracker.core.domain.model

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.validation.ServerConfigValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayTest {
    @Test
    fun `a relay is a domain name with an optional port, never an address`() {
        assertEquals(RelayAddress("relay.example.org", 443), RelayAddress.parse(" Relay.Example.org "))
        assertEquals(RelayAddress("relay.example.org", 8443), RelayAddress.parse("relay.example.org:8443"))
        assertEquals(RelayAddress("relay.example.org", 443), RelayAddress.parse("https://relay.example.org/"))
        listOf("", "localhost", "203.0.113.7", "relay.example.org:0", "relay.example.org:99999", "relay..org", "-relay.example.org", "relay_x.example.org", "[::1]")
            .forEach { assertNull(RelayAddress.parse(it), it) }
        assertNull(RelayAddress.parse("a".repeat(195) + ".example.org"))
    }

    @Test
    fun `a server goes by its fingerprint under the relay's domain, the port only when it is not 443`() {
        val fingerprint = "9E9D-7678-D67D-F283"

        assertEquals("https://9e9d7678d67df283.relay.example.org", relayTrackerUrl(RelayAddress("relay.example.org"), fingerprint))
        assertEquals("https://web-9e9d7678d67df283.relay.example.org:8443", relayWebUrl(RelayAddress("relay.example.org", 8443), fingerprint))
    }

    @Test
    fun `the relay's name comes after the server's own addresses`() {
        val config = ServerConfig(relayAddress = "relay.example.org", relayFingerprint = "1111-2222-3333-4444")

        assertEquals(
            listOf("https://192.168.1.20:8787", "https://9e9d7678d67df283.relay.example.org"),
            advertisedAddresses(listOf("https://192.168.1.20:8787"), config, "9E9D-7678-D67D-F283"),
        )
        assertEquals(listOf("https://192.168.1.20:8787"), advertisedAddresses(listOf("https://192.168.1.20:8787"), ServerConfig(), "9E9D-7678-D67D-F283"))
        // A server whose key does not answer cannot name itself at the relay.
        assertEquals(listOf("https://192.168.1.20:8787"), advertisedAddresses(listOf("https://192.168.1.20:8787"), config, null))
    }

    @Test
    fun `the relay keeps its place however many addresses the server has`() {
        val config = ServerConfig(relayAddress = "relay.example.org", relayFingerprint = "1111-2222-3333-4444")
        val local = (1..10).map { "https://10.0.0.$it:8787" }

        val advertised = advertisedAddresses(local, config, "9E9D-7678-D67D-F283")

        assertEquals(local.take(MAX_ADVERTISED_ADDRESSES - 1) + "https://9e9d7678d67df283.relay.example.org", advertised)
    }

    @Test
    fun `a relay needs a valid name and the fingerprint of its key`() {
        fun violations(address: String, fingerprint: String) =
            ServerConfigValidator.validate(ServerConfig(relayAddress = address, relayFingerprint = fingerprint)).map { it.field to it.rule }

        assertEquals(emptyList(), violations("", ""))
        assertEquals(emptyList(), violations("relay.example.org", "1111-2222-3333-4444"))
        assertEquals(listOf("relayFingerprint" to FieldViolation.REQUIRED), violations("relay.example.org", ""))
        assertEquals(listOf("relayFingerprint" to FieldViolation.INVALID_FORMAT), violations("relay.example.org", "1111"))
        assertEquals(listOf("relayAddress" to FieldViolation.INVALID_FORMAT), violations("203.0.113.7", "1111-2222-3333-4444"))
    }
}
