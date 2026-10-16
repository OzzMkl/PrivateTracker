package org.privatetracker.core.domain.usecase.server

import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.model.NewWebSession
import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.core.domain.port.PasswordHasher
import org.privatetracker.core.domain.repository.WebAccountRepository
import org.privatetracker.core.domain.repository.WebSessionRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

// The web app's accounts and sign-in, from 1.0 on. Accounts are made and removed from the server's
// command line; the web app only signs them in and lets each one change its own password.

/** The form names take, lower-cased: what accounts are stored and looked up by. */
fun normalizeAccountName(name: String): String = name.trim().lowercase()

/** Every rule [name] breaks; empty when an account can have it. */
fun validateAccountName(name: String): List<FieldViolation> {
    val normalized = normalizeAccountName(name)
    return when {
        normalized.isEmpty() -> listOf(FieldViolation("name", FieldViolation.REQUIRED))
        normalized.length > WebAccount.NAME_MAX_LENGTH -> listOf(FieldViolation("name", FieldViolation.TOO_LONG))
        !ACCOUNT_NAME.matches(normalized) -> listOf(FieldViolation("name", FieldViolation.INVALID_FORMAT))
        else -> emptyList()
    }
}

private val ACCOUNT_NAME = Regex("^[a-z0-9][a-z0-9._-]*$")

/**
 * Every rule [password] breaks for the account [name]. Length only, as NIST SP 800-63B-4 asks, plus
 * the passwords anyone would try first: a short run repeated, a keyboard or alphabet sequence, the
 * account's or the app's name, or a few long passwords that top the leaked lists. The whole password
 * is compared, never a part of it.
 */
fun validatePassword(name: String, password: String): List<FieldViolation> {
    val length = password.codePointCount(0, password.length)
    return when {
        length < WebAccount.PASSWORD_MIN_LENGTH -> listOf(FieldViolation("password", FieldViolation.TOO_SHORT))
        length > WebAccount.PASSWORD_MAX_LENGTH -> listOf(FieldViolation("password", FieldViolation.TOO_LONG))
        isCommonPassword(normalizeAccountName(name), password) -> listOf(FieldViolation("password", FieldViolation.TOO_COMMON))
        else -> emptyList()
    }
}

private fun isCommonPassword(name: String, password: String): Boolean {
    val plain = password.lowercase().filterNot { it.isWhitespace() }
    if (plain.isEmpty()) return true
    // "aaaa…", "abcabc…", "passwordpassword": a unit of up to 8 characters, repeated.
    if ((1..minOf(8, plain.length / 2)).any { unit -> plain.length % unit == 0 && plain == plain.take(unit).repeat(plain.length / unit) }) return true
    if (SEQUENCES.any { plain in it || plain in it.reversed() }) return true
    // The account's or the app's name with digits or symbols around it.
    val letters = plain.filter { it.isLetter() }
    if (letters.isNotEmpty() && (letters == name.filter { it.isLetter() } || letters in CONTEXT_WORDS)) return true
    return plain in LEAKED
}

/** Runs of keys and letters a password is often typed along, long enough to hold any 15 of them. */
private val SEQUENCES = listOf(
    "01234567890123456789012345678901234567890",
    "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz",
    "qwertyuiopasdfghjklzxcvbnmqwertyuiopasdfghjklzxcvbnm",
    "qwertyuiopasdfghjklñzxcvbnmqwertyuiopasdfghjklñzxcvbnm",
    "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
    "zaq12wsxcde34rfvbgt56yhnmju78ik,lo90p;",
    "1q2w3e4r5t6y7u8i9o0p",
    "q1w2e3r4t5y6u7i8o9p0",
    "a1b2c3d4e5f6g7h8i9j0",
)

private val CONTEXT_WORDS = setOf(
    "privatetracker", "privatetrackerserver", "privatetrackerweb", "tracker", "server", "admin", "administrator",
    "administrador", "password", "contraseña", "contrasena", "passwordpassword", "letmein", "welcome", "bienvenido",
)

/** Long passwords high on public lists of leaked ones; shorter ones fail the length rule anyway. */
private val LEAKED = setOf(
    "123456789012345", "1234567890123456", "12345678901234567890", "123456789123456789",
    "iloveyouiloveyou", "qwertyuiopasdfgh", "1qaz2wsx3edc4rfv", "1q2w3e4r5t6y7u8i", "qazwsxedcrfvtgbyhn",
    "password12345678", "password123456789", "passw0rdpassw0rd", "p@ssw0rdp@ssw0rd", "iloveyou12345678",
    "abcdefghijklmnop", "11111111111111111", "zaq12wsxcde34rfv", "trustno1trustno1", "masterkeymasterkey",
    "teamoteamoteamo", "contraseña12345", "contrasena12345", "contraseña123456", "contrasena123456",
)

/**
 * Failed and pending sign-ins per key (an address, an account), in a sliding [window]. An attempt counts
 * from the moment it starts, before the slow password check, so a burst of simultaneous guesses cannot
 * all slip under the limit; a success clears the key. Kept in memory: a restart forgets it. Past
 * [maxKeys] the key used least recently goes, so a flood of new keys cannot push out one under attack.
 */
class AttemptLimiter(private val maxAttempts: Int, private val window: Duration, private val maxKeys: Int = 10_000) {
    private val attempts = LinkedHashMap<String, ArrayDeque<Instant>>(16, 0.75f, true)

    /** Counts an attempt for [key] and returns null, or how long to wait when [key] used up its attempts. */
    @Synchronized
    fun tryAcquire(key: String, now: Instant): Duration? {
        val times = attempts.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && !times.first().plus(window).isAfter(now)) times.removeFirst()
        if (times.size >= maxAttempts) return Duration.between(now, times.first().plus(window))
        times.addLast(now)
        // Too many keys, as from a scan of many addresses: the least recently used go. They held attempts, not secrets.
        if (attempts.size > maxKeys) attempts.remove(attempts.keys.first())
        return null
    }

    @Synchronized
    fun reset(key: String) {
        attempts.remove(key)
    }
}

/**
 * Checks a name and password and starts a session. The answer to a wrong name and to a wrong password
 * is the same, and takes as long. Each address gets [ADDRESS_ATTEMPTS] tries and each account
 * [ACCOUNT_ATTEMPTS] per [WINDOW] (NIST allows at most 100 failures in a row per account); a success
 * clears both. A browser the account signed in from before (`knownBrowser`) is not held back by the
 * account's limit, so strangers guessing at a name cannot lock its owner out; its address still counts.
 * A hash made with older settings is redone with the new ones, unless the password changed meanwhile.
 */
class SignIn(
    private val accounts: WebAccountRepository,
    private val sessions: WebSessionRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val byAddress: AttemptLimiter = AttemptLimiter(ADDRESS_ATTEMPTS, WINDOW),
    private val byAccount: AttemptLimiter = accountAttempts(),
) {
    /** What an unknown name is checked against, so it costs the same time as a known one. */
    @Volatile private var decoy: String? = null

    suspend operator fun invoke(
        name: String,
        password: String,
        clientAddress: String?,
        knownBrowser: Boolean = false,
    ): Outcome<NewWebSession> {
        val key = normalizeAccountName(name)
        val addressKey = clientAddress ?: "unknown"
        val now = clock.now()
        byAddress.tryAcquire(addressKey, now)?.let { return tooMany(it) }
        // A name no account can have gets no place in the limiter, and no look-up: only the decoy's time.
        val possible = validateAccountName(key).isEmpty() && password.length <= WebAccount.PASSWORD_MAX_LENGTH * 2
        if (possible && !knownBrowser) byAccount.tryAcquire(key, now)?.let { return tooMany(it) }

        val account = if (possible) accounts.get(key) else null
        val matches = if (account == null) {
            hasher.verify(password, decoy ?: hasher.hash(DECOY_PASSWORD).also { decoy = it })
            false
        } else {
            hasher.verify(password, account.passwordHash)
        }
        if (account == null || !matches) return DomainError.SignInFailed.asFailure()

        byAddress.reset(addressKey)
        byAccount.reset(key)
        if (hasher.isOutdated(account.passwordHash)) {
            // Only over the hash just checked: a password changed meanwhile is not put back.
            accounts.update(account.copy(passwordHash = hasher.hash(password)), ifHash = account.passwordHash)
        }
        return startSession(account, sessions, clock, random).asSuccess()
    }

    companion object {
        const val ADDRESS_ATTEMPTS = 10
        const val ACCOUNT_ATTEMPTS = 50
        val WINDOW: Duration = Duration.ofMinutes(15)
        private const val DECOY_PASSWORD = "not a password anyone has"

        /** The per-account limit, to share with [ChangeWebPassword]'s check of the current password. */
        fun accountAttempts() = AttemptLimiter(ACCOUNT_ATTEMPTS, WINDOW)
    }
}

private fun tooMany(wait: Duration): Outcome<Nothing> =
    DomainError.TooManyAttempts(retryAfterSeconds = maxOf(1, wait.toSeconds())).asFailure()

/** A new session for [account], already stored, tied to the password just checked. */
internal suspend fun startSession(account: WebAccount, sessions: WebSessionRepository, clock: Clock, random: SecureRandom): NewWebSession {
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
    val now = clock.now()
    val session = WebSession(tokenHash(token), account.name, now, now.plus(WebSession.LIFETIME), account.passwordChangedAt)
    sessions.add(session)
    return NewWebSession(token, session)
}

/** What the server stores of a session token: its SHA-256, Base64url. */
fun tokenHash(token: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()))

private const val TOKEN_BYTES = 32
private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

/**
 * The session a browser's token opens, or null when it ended, never existed or is not even a token.
 * It also ends when its account is gone or its password changed after the session's was checked:
 * a sign-in still hashing while the owner ran `users passwd` or `users remove` gets nothing.
 */
class AuthenticateWebSession(
    private val sessions: WebSessionRepository,
    private val accounts: WebAccountRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(token: String?): WebSession? {
        if (token == null || !TOKEN.matches(token)) return null
        val hash = tokenHash(token)
        val session = sessions.get(hash) ?: return null
        val account = accounts.get(session.account)
        if (!session.expiresAt.isAfter(clock.now()) || account == null || account.passwordChangedAt != session.passwordSetAt) {
            sessions.delete(hash)
            return null
        }
        return session
    }
}

class SignOut(private val sessions: WebSessionRepository) {
    suspend operator fun invoke(token: String) = sessions.delete(tokenHash(token))
}

/** Made from the command line, which only the server's own user can reach. */
class CreateWebAccount(
    private val accounts: WebAccountRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock,
) {
    suspend operator fun invoke(name: String, password: String): Outcome<WebAccount> {
        val violations = validateAccountName(name) + validatePassword(name, password)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        // Milliseconds, as the files keep them: sessions compare this time exactly.
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val account = WebAccount(normalizeAccountName(name), hasher.hash(password), now, now)
        if (!accounts.insert(account)) return DomainError.Validation("name", FieldViolation.TAKEN).asFailure()
        return account.asSuccess()
    }
}

/**
 * A new password for [name] ends every session of the account, wherever it was signed in. From the web
 * app the account proves itself with [currentPassword] first, within the same per-account limit as a
 * sign-in; the command line passes none. The browser that changed it gets a fresh session in the answer.
 */
class ChangeWebPassword(
    private val accounts: WebAccountRepository,
    private val sessions: WebSessionRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val attempts: AttemptLimiter = SignIn.accountAttempts(),
) {
    suspend operator fun invoke(name: String, newPassword: String, currentPassword: String? = null): Outcome<NewWebSession> {
        val account = accounts.get(normalizeAccountName(name)) ?: return DomainError.WebAccountNotFound.asFailure()
        if (currentPassword != null) {
            attempts.tryAcquire(account.name, clock.now())?.let { return tooMany(it) }
            if (!hasher.verify(currentPassword, account.passwordHash)) return DomainError.SignInFailed.asFailure()
            attempts.reset(account.name)
        }
        val violations = validatePassword(account.name, newPassword)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        // A later time than the old one, in the milliseconds the files keep, even within the same millisecond.
        val changedAt = maxOf(clock.now(), account.passwordChangedAt.plusMillis(1)).truncatedTo(ChronoUnit.MILLIS)
        val changed = account.copy(passwordHash = hasher.hash(newPassword), passwordChangedAt = changedAt)
        // Over the hash just checked: of two changes at once, one wins and the other is told so.
        if (!accounts.update(changed, ifHash = account.passwordHash)) return DomainError.WebAccountNotFound.asFailure()
        sessions.deleteAll(account.name)
        return startSession(changed, sessions, clock, random).asSuccess()
    }
}

/** Removes the account and signs it out everywhere. */
class RemoveWebAccount(
    private val accounts: WebAccountRepository,
    private val sessions: WebSessionRepository,
) {
    suspend operator fun invoke(name: String): Outcome<Unit> {
        val key = normalizeAccountName(name)
        // Sessions first, and even when the account is gone already: none may outlive it.
        sessions.deleteAll(key)
        if (!accounts.delete(key)) return DomainError.WebAccountNotFound.asFailure()
        return Unit.asSuccess()
    }
}

class ListWebAccounts(private val accounts: WebAccountRepository) {
    suspend operator fun invoke(): List<WebAccount> = accounts.all().sortedBy { it.name }
}

/** Part of the daily maintenance. */
class PurgeExpiredWebSessions(
    private val sessions: WebSessionRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int = sessions.deleteExpired(clock.now())
}
