package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.core.domain.testing.FakeClock
import org.privatetracker.core.domain.testing.FakePasswordHasher
import org.privatetracker.core.domain.testing.InMemoryWebAccounts
import org.privatetracker.core.domain.testing.InMemoryWebSessions
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.successValue
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GOOD_PASSWORD = "caballo bateria grapa correcta"

class WebAccountsTest {
    private val clock = FakeClock()
    private val accounts = InMemoryWebAccounts()
    private val sessions = InMemoryWebSessions()
    private val hasher = FakePasswordHasher()
    private val create = CreateWebAccount(accounts, hasher, clock)
    private val signIn = SignIn(accounts, sessions, hasher, clock)
    private val authenticate = AuthenticateWebSession(sessions, accounts, clock)
    private val changePassword = ChangeWebPassword(accounts, sessions, hasher, clock)

    private fun rules(name: String, password: String) = validatePassword(name, password).map { it.rule }

    @Test
    fun `names are stored lower case and keep to a safe alphabet`() {
        assertEquals(emptyList(), validateAccountName("  Ana.Garcia_2 "))
        assertEquals("ana.garcia_2", normalizeAccountName("  Ana.Garcia_2 "))
        assertEquals(FieldViolation.REQUIRED, validateAccountName("   ").single().rule)
        assertEquals(FieldViolation.INVALID_FORMAT, validateAccountName("ana garcía").single().rule)
        assertEquals(FieldViolation.INVALID_FORMAT, validateAccountName("-ana").single().rule)
        assertEquals(FieldViolation.TOO_LONG, validateAccountName("a".repeat(WebAccount.NAME_MAX_LENGTH + 1)).single().rule)
    }

    @Test
    fun `a password needs 15 characters and no rule about which ones`() {
        assertEquals(listOf(FieldViolation.TOO_SHORT), rules("ana", "Xk9#mQ2!vL7@pR"))
        assertEquals(emptyList(), rules("ana", "Xk9#mQ2!vL7@pR4"))
        assertEquals(emptyList(), rules("ana", "todas en minúscula sin números"))
        // Characters, not UTF-16 units: an emoji counts once.
        assertEquals(listOf(FieldViolation.TOO_SHORT), rules("ana", "🙂".repeat(7)))
        assertEquals(listOf(FieldViolation.TOO_LONG), rules("ana", "x7".repeat(WebAccount.PASSWORD_MAX_LENGTH)))
    }

    @Test
    fun `passwords anyone would try first are refused`() {
        listOf(
            "aaaaaaaaaaaaaaaa",
            "passwordpassword",
            "abcabcabcabcabcabc",
            "123456789012345",
            "zyxwvutsrqponmlkj",
            "qwertyuiopasdfghj",
            "1qaz2wsx3edc4rfv",
            "PrivateTracker2026!",
            "ana.garcia.2026!!!",
            "Contraseña123456",
        ).forEach { assertEquals(listOf(FieldViolation.TOO_COMMON), rules("ana.garcia", it), it) }
        // A common word inside a longer password is fine: the whole password is compared.
        assertEquals(emptyList(), rules("ana", "mi password del servidor de casa"))
    }

    @Test
    fun `an account is created once, under its lower-case name, with the password hashed`() = runTest {
        val account = create("Ana", GOOD_PASSWORD).successValue()

        assertEquals("ana", account.name)
        assertEquals("fake:$GOOD_PASSWORD", accounts.get("ana")?.passwordHash)
        val again = assertIs<DomainError.Validation>(create("ANA", "otra frase bastante larga").failureError())
        assertEquals(FieldViolation.TAKEN, again.violations.single().rule)
        assertIs<DomainError.Validation>(create("luis", "corta").failureError())
    }

    @Test
    fun `signing in stores only the hash of the token the browser keeps`() = runTest {
        create("ana", GOOD_PASSWORD)

        val started = signIn(" Ana ", GOOD_PASSWORD, "10.0.0.5").successValue()

        assertEquals("ana", started.session.account)
        assertEquals(T0.plus(WebSession.LIFETIME), started.session.expiresAt)
        assertNotEquals(started.token, started.session.tokenHash)
        assertEquals(tokenHash(started.token), sessions.all.single().tokenHash)
        assertEquals("ana", authenticate(started.token)?.account)
    }

    @Test
    fun `a wrong name and a wrong password get the same answer after the same work`() = runTest {
        create("ana", GOOD_PASSWORD)

        assertEquals(DomainError.SignInFailed, signIn("ana", "no es la contraseña", "10.0.0.5").failureError())
        val checksForKnownName = hasher.verifications
        assertEquals(DomainError.SignInFailed, signIn("nadie", GOOD_PASSWORD, "10.0.0.5").failureError())

        assertEquals(checksForKnownName * 2, hasher.verifications)
        assertEquals(emptyList(), sessions.all)
    }

    @Test
    fun `an address gets ten tries per window, and a success clears them`() = runTest {
        create("ana", GOOD_PASSWORD)
        repeat(SignIn.ADDRESS_ATTEMPTS - 1) { signIn("ana", "intento equivocado $it", "10.0.0.5") }
        signIn("ana", GOOD_PASSWORD, "10.0.0.5").successValue()

        repeat(SignIn.ADDRESS_ATTEMPTS) { signIn("ana", "intento equivocado $it", "10.0.0.5") }
        val blocked = assertIs<DomainError.TooManyAttempts>(signIn("ana", GOOD_PASSWORD, "10.0.0.5").failureError())
        assertEquals(SignIn.WINDOW.toSeconds(), blocked.retryAfterSeconds)
        // Another address is not held back by this one.
        signIn("ana", GOOD_PASSWORD, "10.0.0.6").successValue()

        clock.advanceBy(SignIn.WINDOW)
        signIn("ana", GOOD_PASSWORD, "10.0.0.5").successValue()
    }

    @Test
    fun `an account gets fifty tries per window from all addresses together`() = runTest {
        create("ana", GOOD_PASSWORD)
        repeat(SignIn.ACCOUNT_ATTEMPTS) { signIn("ana", "intento equivocado", "10.0.${it / 200}.${it % 200}") }

        assertIs<DomainError.TooManyAttempts>(signIn("ana", GOOD_PASSWORD, "192.168.1.9").failureError())
        // The attempts count for the name, whether or not an account has it.
        repeat(SignIn.ACCOUNT_ATTEMPTS) { signIn("nadie", "intento equivocado", "10.1.${it / 200}.${it % 200}") }
        assertIs<DomainError.TooManyAttempts>(signIn("nadie", GOOD_PASSWORD, "192.168.1.9").failureError())
    }

    @Test
    fun `a browser the account signed in from before gets past the account's limit, not past its address's`() = runTest {
        create("ana", GOOD_PASSWORD)
        repeat(SignIn.ACCOUNT_ATTEMPTS) { signIn("ana", "intento de un extraño", "10.0.${it / 200}.${it % 200}") }
        assertIs<DomainError.TooManyAttempts>(signIn("ana", GOOD_PASSWORD, "192.168.1.9").failureError())

        signIn("ana", GOOD_PASSWORD, "192.168.1.9", knownBrowser = true).successValue()
        repeat(SignIn.ADDRESS_ATTEMPTS) { signIn("ana", "intento equivocado", "192.168.1.10", knownBrowser = true) }
        assertIs<DomainError.TooManyAttempts>(signIn("ana", GOOD_PASSWORD, "192.168.1.10", knownBrowser = true).failureError())
    }

    @Test
    fun `names no account can have take no place in the account limit`() = runTest {
        create("ana", GOOD_PASSWORD)
        // Spread over many addresses, so only the account limit could stop them.
        repeat(SignIn.ACCOUNT_ATTEMPTS * 2) { signIn("x".repeat(500 + it), "intento", "10.2.${it / 200}.${it % 200}") }
        repeat(SignIn.ACCOUNT_ATTEMPTS - 1) { signIn("ana", "intento equivocado", "10.3.${it / 200}.${it % 200}") }

        signIn("ana", GOOD_PASSWORD, "192.168.1.9").successValue()
    }

    @Test
    fun `a session started with a password that changed meanwhile opens nothing`() = runTest {
        create("ana", GOOD_PASSWORD)
        val started = signIn("ana", GOOD_PASSWORD, null).successValue()

        // As if `users passwd` ran while this sign-in was still hashing: the session is stored after the change.
        val account = accounts.get("ana")!!
        accounts.update(account.copy(passwordHash = "fake:otra", passwordChangedAt = account.passwordChangedAt.plusSeconds(1)))

        assertNull(authenticate(started.token))
        assertEquals(emptyList(), sessions.all)
    }

    @Test
    fun `a session of an account that is gone opens nothing, and removing it twice still clears its sessions`() = runTest {
        create("ana", GOOD_PASSWORD)
        val started = signIn("ana", GOOD_PASSWORD, null).successValue()
        accounts.delete("ana")

        assertNull(authenticate(started.token))
        val late = WebSession("late", "ana", T0, T0.plus(WebSession.LIFETIME), T0)
        sessions.add(late)
        assertEquals(DomainError.WebAccountNotFound, RemoveWebAccount(accounts, sessions)("ana").failureError())
        assertEquals(emptyList(), sessions.all)
    }

    @Test
    fun `checking the current password counts against the account's limit`() = runTest {
        create("ana", GOOD_PASSWORD)
        repeat(SignIn.ACCOUNT_ATTEMPTS) { changePassword("ana", "otra frase todavía más larga", currentPassword = "no es la actual $it") }

        assertIs<DomainError.TooManyAttempts>(changePassword("ana", "otra frase todavía más larga", currentPassword = GOOD_PASSWORD).failureError())
    }

    @Test
    fun `a hash made with older settings is redone at sign-in`() = runTest {
        accounts.insert(WebAccount("ana", "old:$GOOD_PASSWORD", T0, T0))

        signIn("ana", GOOD_PASSWORD, null).successValue()

        assertEquals("fake:$GOOD_PASSWORD", accounts.get("ana")?.passwordHash)
    }

    @Test
    fun `a session ends at its expiry however often it was used`() = runTest {
        create("ana", GOOD_PASSWORD)
        val started = signIn("ana", GOOD_PASSWORD, null).successValue()

        clock.advanceBy(WebSession.LIFETIME.minusSeconds(1))
        assertNotNull(authenticate(started.token))
        clock.advanceBy(Duration.ofSeconds(1))
        assertNull(authenticate(started.token))
        assertEquals(emptyList(), sessions.all)
    }

    @Test
    fun `tokens that are not ours open nothing`() = runTest {
        assertNull(authenticate(null))
        assertNull(authenticate(""))
        assertNull(authenticate("x".repeat(43)))
        assertNull(authenticate("../../etc/passwd"))
    }

    @Test
    fun `a new password ends every session and keeps the browser that changed it signed in`() = runTest {
        create("ana", GOOD_PASSWORD)
        val laptop = signIn("ana", GOOD_PASSWORD, null).successValue()
        val phone = signIn("ana", GOOD_PASSWORD, null).successValue()
        val newPassword = "otra frase todavía más larga"

        assertEquals(DomainError.SignInFailed, changePassword("ana", newPassword, currentPassword = "no es la actual").failureError())
        val fresh = changePassword("ana", newPassword, currentPassword = GOOD_PASSWORD).successValue()

        assertNull(authenticate(laptop.token))
        assertNull(authenticate(phone.token))
        assertEquals("ana", authenticate(fresh.token)?.account)
        assertEquals(DomainError.SignInFailed, signIn("ana", GOOD_PASSWORD, null).failureError())
        signIn("ana", newPassword, null).successValue()
    }

    @Test
    fun `removing an account signs it out everywhere`() = runTest {
        create("ana", GOOD_PASSWORD)
        val started = signIn("ana", GOOD_PASSWORD, null).successValue()

        RemoveWebAccount(accounts, sessions)("ANA").successValue()

        assertNull(authenticate(started.token))
        assertEquals(DomainError.WebAccountNotFound, RemoveWebAccount(accounts, sessions)("ana").failureError())
    }

    @Test
    fun `maintenance drops only the sessions that ended`() = runTest {
        create("ana", GOOD_PASSWORD)
        signIn("ana", GOOD_PASSWORD, null)
        clock.advanceBy(Duration.ofDays(20))
        signIn("ana", GOOD_PASSWORD, null)
        clock.advanceBy(Duration.ofDays(15))

        assertEquals(1, PurgeExpiredWebSessions(sessions, clock)())
        assertTrue(sessions.all.single().expiresAt.isAfter(clock.now()))
    }
}
