package org.privatetracker.server.linux.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.core.domain.repository.WebAccountRepository
import org.privatetracker.core.domain.repository.WebSessionRepository
import org.privatetracker.server.hostkeys.writeAtomically
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A small JSON file read once and replaced atomically (0600) on every change, before anyone sees the
 * change. Web accounts and sessions are few, and changes rare: sign-ins, not requests. A file that does
 * not parse goes to [unreadable], which decides between starting over and refusing to start.
 */
private class JsonFile<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    empty: () -> T,
    unreadable: (SerializationException) -> T,
) {
    private val mutex = Mutex()
    private var value: T = if (Files.exists(path)) {
        try {
            json.decodeFromString(serializer, Files.readString(path))
        } catch (e: SerializationException) {
            unreadable(e)
        } catch (e: IllegalArgumentException) {
            unreadable(SerializationException(e.message, e))
        }
    } else {
        empty()
    }

    suspend fun <R> read(block: (T) -> R): R = mutex.withLock { block(value) }

    /** [transform] returns the new value, or the same instance to write nothing, and a result. */
    suspend fun <R> update(transform: (T) -> Pair<T, R>): R = mutex.withLock {
        val (next, result) = transform(value)
        if (next !== value) {
            writeAtomically(path, json.encodeToString(serializer, next).encodeToByteArray())
            value = next
        }
        result
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

/** `web-accounts.json`: names and password hashes. */
class JsonFileWebAccountRepository(path: Path) : WebAccountRepository {
    @Serializable
    private data class AccountData(val name: String, val passwordHash: String, val createdAtMillis: Long, val passwordChangedAtMillis: Long)

    @Serializable
    private data class AccountsFile(val accounts: List<AccountData> = emptyList())

    // Never started over: the next change would write the empty list over every account.
    private val file = JsonFile(path, AccountsFile.serializer(), ::AccountsFile) { e ->
        throw IOException("$path no se puede leer (${e.message}); recupéralo del respaldo o bórralo para empezar sin cuentas", e)
    }

    override suspend fun all(): List<WebAccount> = file.read { it.accounts.map { data -> data.toDomain() } }

    override suspend fun get(name: String): WebAccount? = file.read { it.accounts.firstOrNull { data -> data.name == name }?.toDomain() }

    override suspend fun insert(account: WebAccount): Boolean = file.update { current ->
        if (current.accounts.any { it.name == account.name }) current to false else current.copy(accounts = current.accounts + account.toData()) to true
    }

    override suspend fun update(account: WebAccount, ifHash: String?): Boolean = file.update { current ->
        val stored = current.accounts.firstOrNull { it.name == account.name }
        if (stored == null || (ifHash != null && stored.passwordHash != ifHash)) {
            current to false
        } else {
            current.copy(accounts = current.accounts.map { if (it.name == account.name) account.toData() else it }) to true
        }
    }

    override suspend fun delete(name: String): Boolean = file.update { current ->
        if (current.accounts.none { it.name == name }) current to false else current.copy(accounts = current.accounts.filterNot { it.name == name }) to true
    }

    private fun WebAccount.toData() = AccountData(name, passwordHash, createdAt.toEpochMilli(), passwordChangedAt.toEpochMilli())
    private fun AccountData.toDomain() = WebAccount(name, passwordHash, Instant.ofEpochMilli(createdAtMillis), Instant.ofEpochMilli(passwordChangedAtMillis))
}

/** `web-sessions.json`: only the hashes of the tokens, so the file signs nobody in. */
class JsonFileWebSessionRepository(path: Path) : WebSessionRepository {
    @Serializable
    private data class SessionData(
        val tokenHash: String,
        val account: String,
        val createdAtMillis: Long,
        val expiresAtMillis: Long,
        /** 0 in a session from before it was recorded: such a session opens nothing and is dropped at first use. */
        val passwordSetAtMillis: Long = 0,
    )

    @Serializable
    private data class SessionsFile(val sessions: List<SessionData> = emptyList())

    // Sessions are cheap to lose: everyone signs in again.
    private val file = JsonFile(path, SessionsFile.serializer(), ::SessionsFile) { e ->
        LoggerFactory.getLogger("WebSessions").warn("{} does not parse ({}); starting with no sessions", path, e.message)
        SessionsFile()
    }

    override suspend fun add(session: WebSession) = file.update { current ->
        val data = SessionData(session.tokenHash, session.account, session.createdAt.toEpochMilli(), session.expiresAt.toEpochMilli(), session.passwordSetAt.toEpochMilli())
        current.copy(sessions = current.sessions + data) to Unit
    }

    override suspend fun get(tokenHash: String): WebSession? = file.read { current ->
        current.sessions.firstOrNull { it.tokenHash == tokenHash }?.let {
            WebSession(
                it.tokenHash,
                it.account,
                Instant.ofEpochMilli(it.createdAtMillis),
                Instant.ofEpochMilli(it.expiresAtMillis),
                Instant.ofEpochMilli(it.passwordSetAtMillis),
            )
        }
    }

    override suspend fun delete(tokenHash: String) = file.update { current ->
        if (current.sessions.none { it.tokenHash == tokenHash }) current to Unit else current.copy(sessions = current.sessions.filterNot { it.tokenHash == tokenHash }) to Unit
    }

    override suspend fun deleteAll(account: String): Int = remove { it.account == account }

    override suspend fun deleteExpired(now: Instant): Int = remove { it.expiresAtMillis <= now.toEpochMilli() }

    private suspend fun remove(drop: (SessionData) -> Boolean): Int = file.update { current ->
        val (gone, kept) = current.sessions.partition(drop)
        if (gone.isEmpty()) current to 0 else current.copy(sessions = kept) to gone.size
    }
}
