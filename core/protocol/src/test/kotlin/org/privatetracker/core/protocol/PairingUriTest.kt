package org.privatetracker.core.protocol

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.model.ServerIdentity
import org.privatetracker.core.domain.model.ServerInfo
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.successValue
import org.privatetracker.core.protocol.crypto.InMemoryServerKeys
import org.privatetracker.core.protocol.mapper.toDomain
import org.privatetracker.core.protocol.mapper.toDto
import org.privatetracker.core.protocol.v1.PairingUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingUriTest {
    private suspend fun invite() = PairingInvite(
        serverName = "Casa de Ana & Luis + 50% = ñ",
        serverUrls = listOf("https://192.168.1.50:8787", "https://100.101.102.103:8787"),
        serverKey = InMemoryServerKeys().publicKey(),
        ticketId = "tK3_x-9aBcDe",
        secret = "AAECAwQFBgcICQoLDA0ODw",
        expiresAt = T0.plusSeconds(600),
    )

    @Test
    fun `an invite survives the trip through its QR link`() = runTest {
        val invite = invite()

        val link = PairingUri.format(invite)

        assertTrue(link.startsWith("privatetracker://pair?v=1&"), link)
        assertEquals(invite, PairingUri.parse(link))
        // Small enough for a QR code a phone camera reads at arm's length.
        assertTrue(link.length < 400, "${link.length} characters")
    }

    @Test
    fun `links that are not invites, or that miss or bend any part, are refused`() = runTest {
        val link = PairingUri.format(invite())

        listOf(
            "https://example.com/pair?v=1",
            link.replace("privatetracker://pair", "privatetracker://other"),
            link.replace("v=1", "v=2"),
            link.replace(Regex("&secret=[^&]*"), ""),
            link.replace(Regex("secret=[^&]*"), "secret=AAAA"),
            link.replace(Regex("&url=[^&]*"), ""),
            link.replace(Regex("url=[^&]*"), "url=ftp%3A%2F%2Fhost"),
            // A server from before 0.4, with plain HTTP addresses only.
            link.replace("https%3A", "http%3A"),
            link.replace(Regex("expires=[^&]*"), "expires=soon"),
            link.replace(Regex("ticket=[^&]*"), "ticket=has%20space"),
            "not a link at all",
        ).forEach { assertNull(PairingUri.parse(it), it) }
        // Extra parameters from a newer version are ignored, not fatal.
        assertNotNull(PairingUri.parse("$link&future=1"))
    }

    @Test
    fun `health carries the server identity both ways, and only when complete`() {
        val info = ServerInfo("Casa", "0.3.0", 1, T0, ServerIdentity("a2V5", "c2ln"))
        assertEquals(info, info.toDto().toDomain().successValue())
        assertNull(info.toDto().copy(signature = null).toDomain().successValue().identity)
    }
}
