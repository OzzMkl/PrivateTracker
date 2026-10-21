package org.privatetracker.server.linux

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Test
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.model.RelayAddress
import org.privatetracker.server.linux.keys.PemFiles
import org.privatetracker.server.linux.platform.AlertWebhook
import org.privatetracker.server.linux.platform.WebhookFormat
import org.privatetracker.server.linux.platform.inviteUrls
import org.privatetracker.server.web.MapTiles
import org.privatetracker.server.web.qrMatrix
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandLineTest {
    @Test
    fun `options take a value, flags do not, and words after the command are positional`() {
        val arguments = Arguments.parse(listOf("approve", "6f1c", "--data", "/srv/pt", "--all-pending", "--port=9000"))

        assertEquals("approve", arguments.command)
        assertEquals(listOf("6f1c"), arguments.positional)
        assertEquals("/srv/pt", arguments.value("data"))
        assertEquals("9000", arguments.value("port"))
        assertTrue(arguments.flag("all-pending"))
        assertFailsWith<UsageException> { Arguments.parse(listOf("serve", "--port")) }
    }

    @Test
    fun `serve takes each setting from its flag, else its environment variable, else the phone's default`() {
        val settings = serveSettings(
            Arguments.parse(listOf("serve", "--port", "9443", "--public-url", "tracker.example.org:9443")),
            mapOf(
                "PRIVATETRACKER_PORT" to "1111",
                "PRIVATETRACKER_NAME" to "Casa",
                "PRIVATETRACKER_RETENTION_DAYS" to "90",
                "PRIVATETRACKER_PUBLIC_URLS" to "https://ignored.example.org",
            ),
        )

        assertEquals(9443, settings.config.port)
        assertEquals("Casa", settings.config.serverName)
        assertEquals(90, settings.config.retentionDays)
        assertEquals("0.0.0.0", settings.config.bindAddress)
        assertFalse(settings.config.advertiseOnLan)
        assertEquals(listOf("https://tracker.example.org:9443"), settings.publicUrls)
    }

    @Test
    fun `serve refuses what the phone's settings screen refuses, naming the option`() {
        fun refusal(vararg args: String, environment: Map<String, String> = emptyMap()) =
            assertFailsWith<UsageException> { serveSettings(Arguments.parse(listOf("serve") + args), environment) }.message

        assertEquals("--port debe estar entre 1024 y 65535", refusal("--port", "80"))
        assertEquals("--port debe ser un número: «abc»", refusal("--port", "abc"))
        assertEquals("--retention-days debe estar entre 1 y 365 días", refusal(environment = mapOf("PRIVATETRACKER_RETENTION_DAYS" to "0")))
        assertEquals("URL pública no válida «http://casa:8787»: la URL debe empezar con https://", refusal("--public-url", "http://casa:8787"))
    }

    @Test
    fun `the web app is on, on its own port, unless the owner sets 0`() {
        val defaults = serveSettings(Arguments.parse(listOf("serve")), emptyMap())
        assertEquals(WebSettings(port = 8788, bindAddress = "0.0.0.0", certificate = null), defaults.web)
        assertEquals(MapTiles.OPEN_STREET_MAP, defaults.tiles)
        assertNull(defaults.webhook)

        assertNull(serveSettings(Arguments.parse(listOf("serve")), mapOf("PRIVATETRACKER_WEB_PORT" to "0")).web)
        val own = serveSettings(
            Arguments.parse(listOf("serve", "--web-port", "9443", "--web-bind", "100.64.0.7")),
            mapOf("PRIVATETRACKER_WEB_CERT" to "/certs/casa.crt", "PRIVATETRACKER_WEB_KEY" to "/certs/casa.key"),
        ).web
        assertEquals(WebSettings(9443, "100.64.0.7", PemFiles(Path.of("/certs/casa.crt"), Path.of("/certs/casa.key"))), own)
    }

    @Test
    fun `the web settings refuse a shared port, a certificate without its key, and tiles over http`() {
        fun refused(vararg args: String, env: Map<String, String> = emptyMap()) =
            assertFailsWith<UsageException> { serveSettings(Arguments.parse(listOf("serve", *args)), env) }.message!!

        assertTrue("--web-port" in refused("--port", "9000", "--web-port", "9000"))
        assertTrue("--web-key" in refused("--web-cert", "/c.pem"))
        assertTrue("--tile-url" in refused("--tile-url", "http://tiles.example.org/{z}/{x}/{y}.png"))
        assertTrue("--tile-url" in refused("--tile-url", "https://tiles.example.org/tile.png"))
        assertTrue("--alert-webhook" in refused("--alert-webhook", "ftp://example.org"))
        assertTrue("--alert-webhook-format" in refused("--alert-webhook", "https://example.org/hook", "--alert-webhook-format", "xml"))
    }

    @Test
    fun `an ntfy webhook is told apart by its host, and the token stays out of the target shown`() {
        val ntfy = serveSettings(Arguments.parse(listOf("serve")), mapOf("PRIVATETRACKER_ALERT_WEBHOOK" to "https://ntfy.sh/casa-ana-7f3")).webhook!!
        val gotify = serveSettings(
            Arguments.parse(listOf("serve", "--alert-webhook", "https://gotify.casa.lan/message?token=secreto")),
            mapOf("PRIVATETRACKER_ALERT_WEBHOOK_TOKEN" to "abc"),
        ).webhook!!

        assertEquals(WebhookFormat.NTFY, ntfy.format)
        assertEquals(WebhookFormat.JSON, gotify.format)
        assertEquals("abc", gotify.token)
        val target = AlertWebhook(gotify, "Casa", kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), org.privatetracker.core.common.time.Clock.System).target
        assertEquals("https://gotify.casa.lan", target)
    }

    @Test
    fun `a relay is set by name and fingerprint, and its web app only with a web app to offer`() {
        val settings = serveSettings(
            Arguments.parse(listOf("serve", "--relay", "Relay.Example.org:8443", "--relay-web", "true")),
            mapOf("PRIVATETRACKER_RELAY_FINGERPRINT" to "9e9d 7678 d67d f283"),
        )
        assertEquals(RelayAddress("relay.example.org", 8443), settings.config.relay)
        assertEquals("9E9D-7678-D67D-F283", settings.config.relayFingerprint)
        assertTrue(settings.config.relayWeb)

        fun refused(vararg args: String) = assertFailsWith<UsageException> { serveSettings(Arguments.parse(listOf("serve", *args)), emptyMap()) }.message!!
        assertTrue("--relay-fingerprint" in refused("--relay", "relay.example.org"))
        assertTrue("--relay" in refused("--relay", "203.0.113.7", "--relay-fingerprint", "9E9D-7678-D67D-F283"))
        assertTrue("--relay-web" in refused("--relay", "relay.example.org", "--relay-fingerprint", "9E9D-7678-D67D-F283", "--relay-web", "true", "--web-port", "0"))
    }

    @Test
    fun `new devices are accepted unless the owner turns it off`() {
        assertTrue(serveSettings(Arguments.parse(listOf("serve")), emptyMap()).config.acceptNewDevices)
        assertFalse(serveSettings(Arguments.parse(listOf("serve")), mapOf("PRIVATETRACKER_ACCEPT_NEW_DEVICES" to "false")).config.acceptNewDevices)
        assertFailsWith<UsageException> { serveSettings(Arguments.parse(listOf("serve", "--accept-new-devices", "quizá")), emptyMap()) }
    }

    @Test
    fun `names from the network print without escape sequences or direction overrides`() {
        assertEquals("Pixel de Ana 📍", printable("Pixel de Ana 📍"))
        assertEquals("?[2J?[HFalso", printable("\u001b[2J\u001b[HFalso"))
        assertEquals("abc?gnp.exe", printable("abc\u202Egnp.exe"))
        assertEquals("a?b", printable("a\nb"))
    }

    @Test
    fun `invites list the public URLs first and, outside a container, the host's LAN and VPN addresses`() {
        val addresses = listOf(
            NetworkAddress("eth0", "192.168.1.20"),
            NetworkAddress("tailscale0", "100.101.102.103"),
            NetworkAddress("lo", "127.0.0.1"),
            NetworkAddress("eth1", "203.0.113.7"),
        )

        assertEquals(
            listOf("https://casa.example.org:8787", "https://192.168.1.20:8787", "https://100.101.102.103:8787"),
            inviteUrls(listOf("https://casa.example.org:8787"), 8787, "0.0.0.0", inContainer = false, addresses),
        )
        // A container's own addresses mean nothing to a phone.
        assertEquals(emptyList(), inviteUrls(emptyList(), 8787, "0.0.0.0", inContainer = true, addresses))
        // Bound to one address, the server is reachable there only.
        assertEquals(listOf("https://192.168.1.20:8787"), inviteUrls(emptyList(), 8787, "192.168.1.20", inContainer = false, addresses))
    }

    @Test
    fun `the terminal QR decodes back to the invite`() {
        val uri = "privatetracker://pair?v=1&name=Casa&url=https%3A%2F%2F192.168.1.20%3A8787&ticket=abc&secret=def&expires=1792010100"

        val drawn = terminalQr(qrMatrix(uri))

        assertEquals(uri, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(AnsiHalfBlocks(drawn)))).text)
    }

    @Test
    fun `the terminal QR has a light quiet zone around it`() {
        val matrix = qrMatrix("privatetracker://pair?v=1")
        val lines = terminalQr(matrix).trimEnd('\n').lines()

        assertEquals((matrix.height + 1) / 2, lines.size)
        assertTrue(lines.first().startsWith("\u001b[97;107m▀"))
    }
}

/** Reads the colors back out of [terminalQr]'s output, [SCALE] pixels per module, for a decoder to scan. */
private class AnsiHalfBlocks(drawn: String) : LuminanceSource(modules(drawn).width * SCALE, modules(drawn).height * SCALE) {
    private val modules = modules(drawn)

    override fun getRow(y: Int, row: ByteArray?): ByteArray =
        (row ?: ByteArray(width)).also { for (x in 0 until width) it[x] = luminance(x, y) }

    override fun getMatrix(): ByteArray = ByteArray(width * height) { luminance(it % width, it / width) }

    private fun luminance(x: Int, y: Int): Byte = if (modules[x / SCALE, y / SCALE]) 0 else 255.toByte()

    companion object {
        const val SCALE = 4
        private val CELL = Regex("\u001b\\[(\\d+);(\\d+)m▀")

        /** Upper halves are the foreground color (30 dark), lower halves the background (40 dark). */
        fun modules(drawn: String): BitMatrix {
            val lines = drawn.trimEnd('\n').lines().map { line -> CELL.findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            val matrix = BitMatrix(lines.first().size, lines.size * 2)
            lines.forEachIndexed { line, cells ->
                cells.forEachIndexed { x, (top, bottom) ->
                    if (top == "30") matrix.set(x, line * 2)
                    if (bottom == "40") matrix.set(x, line * 2 + 1)
                }
            }
            return matrix
        }
    }
}
