package org.privatetracker.server.web

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.GeofenceShape
import org.privatetracker.core.domain.testing.DEVICE_A
import org.privatetracker.core.domain.testing.DEVICE_B
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aDevice
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.usecase.server.SignIn
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.server.web.security.SESSION_COOKIE
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAppTest {
    private val bed = WebTestbed()

    @Test
    fun `every page sends a browser without a session to sign in first`() = bed.run {
        val client = browser()
        listOf("/", "/devices", "/geofences", "/server", "/pair", "/account", "/devices/${DEVICE_A.value}/history").forEach { path ->
            val response = client.getPage(path, cookie = null)
            assertEquals(HttpStatusCode.SeeOther, response.status, path)
            assertTrue(response.headers[HttpHeaders.Location]!!.startsWith("/login"), path)
        }
        assertEquals("/login?next=%2Fdevices", client.getPage("/devices", null).headers[HttpHeaders.Location])
        // Scripts and the live stream get a status they can read, not a page.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/live").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/session").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/devices/section") { header("HX-Request", "true") }.status)
        assertEquals("/login", client.get("/devices/section") { header("HX-Request", "true") }.headers["HX-Redirect"])
        // A cookie that is no session is no better.
        assertEquals(HttpStatusCode.SeeOther, client.getPage("/", "x".repeat(43)).status)
    }

    @Test
    fun `the first visit explains how to make an account`() = bed.run {
        val page = browser().getPage("/login", null).bodyAsText()
        assertContains(page, "privatetracker-server users add")
        bed.account()
        assertFalse(browser().getPage("/login", null).bodyAsText().contains("users add"))
    }

    @Test
    fun `signing in sets a host-only, secure, script-proof, same-site cookie and goes where it was asked`() = bed.run {
        bed.account()
        val client = browser()

        val response = client.submitForm(
            "/login",
            Parameters.build {
                append("name", "Ana")
                append("password", PASSWORD)
                append("next", "/geofences")
            },
        ) { fromThisSite() }

        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/geofences", response.headers[HttpHeaders.Location])
        val cookie = response.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith(SESSION_COOKIE) }
        listOf("Secure", "HttpOnly", "SameSite=Strict", "Path=/").forEach { assertContains(cookie, it) }
        assertFalse(cookie.contains("Domain", ignoreCase = true))
        val token = sessionCookie(response)!!
        assertEquals(HttpStatusCode.OK, client.getPage("/geofences", token).status)
        assertEquals(HttpStatusCode.NoContent, client.get("/session") { signedIn(token) }.status)
    }

    @Test
    fun `a wrong password gets the form again, and too many get a wait`() = bed.run {
        bed.account()
        val client = browser()
        val wrong = Parameters.build {
            append("name", "ana")
            append("password", "no es la contraseña")
        }

        val first = client.submitForm("/login", wrong) { fromThisSite() }
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        assertContains(first.bodyAsText(), "Nombre o contraseña incorrectos")
        assertNull(sessionCookie(first))

        repeat(9) { client.submitForm("/login", wrong) { fromThisSite() } }
        val blocked = client.submitForm("/login", wrong) { fromThisSite() }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("900", blocked.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `after signing in it never sends the browser to another site`() = bed.run {
        bed.account()
        // Browsers drop tabs and newlines from URLs and read a backslash as a slash.
        listOf(
            "//evil.example/x", "https://evil.example", "/\\evil.example", "/\t/evil.example", "/\n/evil.example", "/ /evil.example",
            "/x\\..\\..\\evil", "javascript:alert(1)", "/login?next=//x",
        ).forEach { next ->
            val response = browser().submitForm(
                "/login",
                Parameters.build {
                    append("name", "ana")
                    append("password", PASSWORD)
                    append("next", next)
                },
            ) { fromThisSite() }
            assertEquals("/", response.headers[HttpHeaders.Location], next)
        }
    }

    @Test
    fun `a browser that signed in before gets in even while strangers use up the account's attempts`() = bed.run {
        bed.account()
        val client = browser()
        val form = Parameters.build {
            append("name", "ana")
            append("password", PASSWORD)
        }
        val first = client.submitForm("/login", form) { fromThisSite() }
        val known = first.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("__Host-pt-known=") }
        listOf("Secure", "HttpOnly", "SameSite=Strict").forEach { assertContains(known, it) }
        // Fifty strangers on fifty addresses (in this engine every request comes from one, so they go to the use case).
        repeat(SignIn.ACCOUNT_ATTEMPTS) { runBlocking { bed.deps.signIn("ana", "intento de un extraño", "10.0.0.$it") } }

        val stranger = client.submitForm("/login", form) { fromThisSite() }
        val forged = client.submitForm("/login", form) {
            fromThisSite()
            header(HttpHeaders.Cookie, "__Host-pt-known=ana.AAAA")
        }
        val owner = client.submitForm("/login", form) {
            fromThisSite()
            header(HttpHeaders.Cookie, known.substringBefore(';'))
        }

        assertEquals(HttpStatusCode.TooManyRequests, stranger.status)
        assertEquals(HttpStatusCode.TooManyRequests, forged.status, "a forged cookie is no better than none")
        assertEquals(HttpStatusCode.SeeOther, owner.status)
    }

    @Test
    fun `a form posted from another site is refused before anything happens`() = bed.run {
        bed.account()
        bed.device(aDevice(approval = DeviceApproval.PENDING))
        val client = browser()
        val token = client.signIn()

        val crossSite = client.post("/devices/${DEVICE_A.value}/approve") {
            signedIn(token)
            header("Sec-Fetch-Site", "cross-site")
        }
        val noHeaders = client.post("/devices/${DEVICE_A.value}/approve") { signedIn(token) }
        val otherOrigin = client.post("/devices/${DEVICE_A.value}/approve") {
            signedIn(token)
            header(HttpHeaders.Origin, "https://evil.example")
        }
        // None of them changed anything.
        assertEquals(DeviceApproval.PENDING, bed.store.get(DEVICE_A)?.approval)
        val sameOrigin = client.post("/devices/${DEVICE_A.value}/approve") {
            signedIn(token)
            // A browser without Fetch Metadata: Origin must name this very host and port.
            header(HttpHeaders.Host, "casa.example:8788")
            header(HttpHeaders.Origin, "https://casa.example:8788")
        }

        assertEquals(HttpStatusCode.Forbidden, crossSite.status)
        assertEquals(HttpStatusCode.Forbidden, noHeaders.status)
        assertEquals(HttpStatusCode.Forbidden, otherOrigin.status)
        assertEquals(HttpStatusCode.SeeOther, sameOrigin.status)
        assertEquals(DeviceApproval.APPROVED, bed.store.get(DEVICE_A)?.approval)
    }

    @Test
    fun `bodies larger than any form, or of unknown length, are refused`() = bed.run {
        val client = browser()
        val large = client.post("/login") {
            fromThisSite()
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=" + "a".repeat(70_000))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, large.status)
    }

    @Test
    fun `pages carry a CSP without inline code, and names from the network cannot break out of the page's data`() = bed.run {
        bed.account()
        bed.device(aDevice(name = "</script><script>alert(1)</script>"), aLocation(1))
        val client = browser()
        val token = client.signIn()

        val response = client.getPage("/", token)
        val page = response.bodyAsText()

        val csp = response.headers["Content-Security-Policy"]!!
        assertContains(csp, "script-src 'self'")
        assertFalse(csp.contains("unsafe-inline"))
        assertContains(csp, "img-src 'self' data: blob: https://tile.openstreetmap.org")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertFalse(page.contains("<script>alert(1)"))
        assertContains(page, "\\u003c/script>\\u003cscript>alert(1)")
        assertContains(page, "&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;")
        // No script element with code in it: every one either loads a file or holds JSON.
        Regex("<script([^>]*)>([^<]*)</script>").findAll(page).forEach { match ->
            assertTrue("src=" in match.groupValues[1] || "application/json" in match.groupValues[1], match.value)
        }
    }

    @Test
    fun `the owner approves a pending device and htmx gets the new list`() = bed.run {
        bed.account()
        bed.device(aDevice(approval = DeviceApproval.PENDING, name = "Moto de Luis"))
        val client = browser()
        val token = client.signIn()
        assertContains(client.getPage("/devices", token).bodyAsText(), "Esperan tu aprobación")

        val response = client.post("/devices/${DEVICE_A.value}/approve") {
            signedIn(token)
            fromThisSite()
            header("HX-Request", "true")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val fragment = response.bodyAsText()
        assertTrue(fragment.startsWith("<section id=\"devices\""), fragment.take(80))
        assertFalse(fragment.contains("Esperan tu aprobación"))
        assertEquals(DeviceApproval.APPROVED, bed.store.get(DEVICE_A)?.approval)
    }

    @Test
    fun `removing a device takes its history with it`() = bed.run {
        bed.account()
        bed.device(aDevice(), aLocation(1))
        val client = browser()
        val token = client.signIn()

        val response = client.post("/devices/${DEVICE_A.value}/remove") {
            signedIn(token)
            fromThisSite()
        }

        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertNull(bed.store.get(DEVICE_A))
        assertEquals(HttpStatusCode.NotFound, client.getPage("/devices/${DEVICE_A.value}/history", token).status)
    }

    @Test
    fun `the history shows the day's route and exports exactly that range`() = bed.run {
        bed.account()
        bed.device(
            aDevice(name = "Pixel de Ana"),
            aLocation(1, recordedAt = T0.minusSeconds(600)),
            aLocation(2, latitude = 19.4426, recordedAt = T0.minusSeconds(300)),
            aLocation(3, latitude = 19.4526, recordedAt = T0),
        )
        val client = browser()
        val token = client.signIn()

        val page = client.get("/devices/${DEVICE_A.value}/history?period=today") {
            page()
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$token; pt-tz=UTC")
        }.bodyAsText()
        assertContains(page, "en 3 posiciones")
        assertContains(page, "2.2 km")
        val link = Regex("/devices/[^\"]+/history\\.csv\\?start=(\\d+)&amp;end=(\\d+)").find(page)!!
        val (start, end) = link.destructured

        val export = client.get("/devices/${DEVICE_A.value}/history.csv?start=$start&end=$end") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$token; pt-tz=UTC")
        }

        assertEquals(HttpStatusCode.OK, export.status)
        assertContains(export.headers[HttpHeaders.ContentDisposition]!!, "attachment; filename=\"PrivateTracker-Pixel_de_Ana-2026-10-02.csv\"")
        assertEquals(4, export.bodyAsText().trim().lines().size)
    }

    @Test
    fun `at 23 57 the last 7 days end today, in the page and in the file name`() = bed.run {
        bed.account()
        bed.device(aDevice(name = "Pixel de Ana"), aLocation(1, recordedAt = T0))
        bed.clock.current = java.time.Instant.parse("2026-10-02T23:57:00Z")
        val client = browser()
        val token = client.signIn()

        val page = client.get("/devices/${DEVICE_A.value}/history?period=7d") {
            page()
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$token; pt-tz=UTC")
        }.bodyAsText()
        assertContains(page, "name=\"to\" value=\"2026-10-02\"")
        val (start, end) = Regex("history\\.gpx\\?start=(\\d+)&amp;end=(\\d+)").find(page)!!.destructured

        val export = client.get("/devices/${DEVICE_A.value}/history.gpx?start=$start&end=$end") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=$token; pt-tz=UTC")
        }
        assertContains(export.headers[HttpHeaders.ContentDisposition]!!, "PrivateTracker-Pixel_de_Ana-2026-09-25_2026-10-02.gpx")
    }

    @Test
    fun `a code that expired says so, even after the server withdrew it`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()
        val ticket = client.post("/pair") { signedIn(token); fromThisSite() }.headers[HttpHeaders.Location]!!.substringAfterLast('/')

        bed.clock.advanceBy(java.time.Duration.ofMinutes(11))
        bed.tickets.withdraw(ticket)

        val status = client.get("/pair/$ticket/status") { signedIn(token); header("HX-Request", "true") }.bodyAsText()
        assertContains(status, "El código venció")
    }

    @Test
    fun `a geofence the domain refuses comes back with the owner's input and a reason`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()

        val refused = client.submitForm(
            "/geofences",
            Parameters.build {
                append("name", "Casa")
                append("kind", "circle")
                append("radius", "20")
                append("shape", """{"kind":"circle","center":[-99.1332,19.4326]}""")
                append("devices", "all")
                append("enter", "on")
            },
        ) {
            signedIn(token)
            fromThisSite()
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        val page = refused.bodyAsText()
        assertContains(page, "El radio debe estar entre 50 m y 50 km.")
        assertContains(page, "value=\"Casa\"")

        val saved = client.submitForm(
            "/geofences",
            Parameters.build {
                append("name", "Casa")
                append("kind", "circle")
                append("radius", "150")
                append("shape", """{"kind":"circle","center":[-99.1332,19.4326]}""")
                append("devices", "some")
                append("device", DEVICE_B.value)
                append("enter", "on")
            },
        ) {
            signedIn(token)
            fromThisSite()
        }
        assertEquals(HttpStatusCode.SeeOther, saved.status)
        val geofence = bed.geofences.observeAll().first().single()
        assertEquals("Casa", geofence.name)
        assertEquals(150.0, assertIs<GeofenceShape.Circle>(geofence.shape).radiusM)
        assertEquals(setOf(DEVICE_B), geofence.deviceIds)
        assertTrue(geofence.notifyOnEnter)
        assertFalse(geofence.notifyOnExit)
    }

    @Test
    fun `a circle without a center asks for one`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()

        val refused = client.submitForm(
            "/geofences",
            Parameters.build {
                append("name", "Casa")
                append("kind", "circle")
                append("radius", "150")
                append("shape", "not json")
                append("devices", "all")
            },
        ) {
            signedIn(token)
            fromThisSite()
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertContains(refused.bodyAsText(), "Toca el mapa para poner el centro.")
    }

    @Test
    fun `the QR shown in the browser pairs, then stops being offered`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()

        val created = client.post("/pair") {
            signedIn(token)
            fromThisSite()
        }
        assertEquals(HttpStatusCode.SeeOther, created.status)
        val pagePath = created.headers[HttpHeaders.Location]!!
        val ticket = pagePath.substringAfterLast('/')
        val page = client.getPage(pagePath, token).bodyAsText()
        assertContains(page, "<svg")
        val uri = Regex("value=\"(privatetracker://pair[^\"]+)\"").find(page)!!.groupValues[1].replace("&amp;", "&")
        assertEquals(ticket, PairingUri.parse(uri)?.ticketId)

        val waiting = client.get("/pair/$ticket/status") { signedIn(token); header("HX-Request", "true") }.bodyAsText()
        assertContains(waiting, "hx-trigger=\"every 2s\"")

        bed.device(aDevice(name = "Pixel de Ana"))
        bed.tickets.consume(ticket, DEVICE_A)
        val paired = client.get("/pair/$ticket/status") { signedIn(token); header("HX-Request", "true") }.bodyAsText()
        assertContains(paired, "Emparejado: Pixel de Ana")
        assertFalse(paired.contains("every 2s"))
        assertContains(paired, "hx-swap-oob=\"true\"")
    }

    @Test
    fun `an invite nobody looks at any more is withdrawn`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()
        val ticket = client.post("/pair") { signedIn(token); fromThisSite() }.headers[HttpHeaders.Location]!!.substringAfterLast('/')
        assertNotNull(bed.tickets.get(ticket))

        bed.clock.advanceBy(WebPairing.ABANDONED_AFTER.plusSeconds(1))
        withTimeout(10_000) { while (bed.tickets.get(ticket) != null) kotlinx.coroutines.delay(100) }

        val ended = client.get("/pair/$ticket/status") { signedIn(token); header("HX-Request", "true") }.bodyAsText()
        assertContains(ended, "Este código ya no vale")
    }

    @Test
    fun `signing out ends the session for good`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()

        val response = client.post("/logout") {
            signedIn(token)
            fromThisSite()
        }

        assertEquals("/login", response.headers[HttpHeaders.Location])
        val cleared = response.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("$SESSION_COOKIE=") }
        assertTrue(cleared.startsWith("$SESSION_COOKIE=;"), cleared)
        assertContains(cleared, "Max-Age=0")
        assertEquals(HttpStatusCode.SeeOther, client.getPage("/", token).status)
        assertEquals(0, bed.sessions.all.size)
    }

    @Test
    fun `changing the password keeps this browser in and signs out the others`() = bed.run {
        bed.account()
        val client = browser()
        val laptop = client.signIn()
        val phone = client.signIn()

        val response = client.submitForm(
            "/account/password",
            Parameters.build {
                append("current", PASSWORD)
                append("password", "una frase nueva y bastante larga")
                append("repeat", "una frase nueva y bastante larga")
            },
        ) {
            signedIn(laptop)
            fromThisSite()
        }

        // A 303, so a reload does not post the old password again.
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/account?done=password", response.headers[HttpHeaders.Location])
        val fresh = sessionCookie(response)!!
        assertContains(client.getPage("/account?done=password", fresh).bodyAsText(), "Contraseña cambiada")
        assertEquals(HttpStatusCode.OK, client.getPage("/", fresh).status)
        assertEquals(HttpStatusCode.SeeOther, client.getPage("/", laptop).status)
        assertEquals(HttpStatusCode.SeeOther, client.getPage("/", phone).status)
    }

    @Test
    fun `assets are versioned and kept for a year, old versions are not cached`() = bed.run {
        bed.account()
        val client = browser()
        val page = client.getPage("/login", null).bodyAsText()
        val css = Regex("/assets/([0-9a-f]+)/app\\.css").find(page)!!

        val current = client.get(css.value)
        assertEquals(HttpStatusCode.OK, current.status)
        assertEquals("public, max-age=31536000, immutable", current.headers[HttpHeaders.CacheControl])
        assertTrue(current.headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
        assertEquals("no-store", client.get("/assets/0000/app.css").headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/${css.groupValues[1]}/../../etc/passwd").status)
        assertEquals(HttpStatusCode.OK, client.get("/assets/${css.groupValues[1]}/vendor/maplibre-gl/maplibre-gl-csp-worker.js").status)
    }

    @Test
    fun `the server page shows the fingerprints and rotates the key`() = bed.run {
        bed.account()
        val client = browser()
        val token = client.signIn()
        val before = runBlocking { bed.deps.encryptionKeys.current().key.id }

        val page = client.getPage("/server", token).bodyAsText()
        assertContains(page, before)
        assertContains(page, "AB:CD")

        val rotated = client.post("/server/rotate-key") { signedIn(token); fromThisSite() }
        assertEquals("/server?done=rotated", rotated.headers[HttpHeaders.Location])
        val after = runBlocking { bed.deps.encryptionKeys.current().key.id }
        assertFalse(before == after)
    }
}
