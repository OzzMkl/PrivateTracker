package org.privatetracker.core.domain.testing

import org.privatetracker.core.domain.model.WebAccount
import org.privatetracker.core.domain.model.WebSession
import org.privatetracker.core.domain.port.PasswordHasher
import org.privatetracker.core.domain.repository.WebAccountRepository
import org.privatetracker.core.domain.repository.WebSessionRepository
import java.time.Instant

class InMemoryWebAccounts : WebAccountRepository {
    private val accounts = linkedMapOf<String, WebAccount>()

    override suspend fun all(): List<WebAccount> = synchronized(this) { accounts.values.toList() }
    override suspend fun get(name: String): WebAccount? = synchronized(this) { accounts[name] }

    override suspend fun insert(account: WebAccount): Boolean = synchronized(this) {
        if (account.name in accounts) return false
        accounts[account.name] = account
        true
    }

    override suspend fun update(account: WebAccount, ifHash: String?): Boolean = synchronized(this) {
        val current = accounts[account.name] ?: return false
        if (ifHash != null && current.passwordHash != ifHash) return false
        accounts[account.name] = account
        true
    }

    override suspend fun delete(name: String): Boolean = synchronized(this) { accounts.remove(name) != null }
}

class InMemoryWebSessions : WebSessionRepository {
    private val sessions = linkedMapOf<String, WebSession>()

    val all: List<WebSession> get() = synchronized(this) { sessions.values.toList() }

    override suspend fun add(session: WebSession) = synchronized(this) { sessions[session.tokenHash] = session }
    override suspend fun get(tokenHash: String): WebSession? = synchronized(this) { sessions[tokenHash] }
    override suspend fun delete(tokenHash: String) = synchronized(this) { sessions.remove(tokenHash); Unit }

    override suspend fun deleteAll(account: String): Int = synchronized(this) {
        val ended = sessions.values.filter { it.account == account }
        ended.forEach { sessions.remove(it.tokenHash) }
        ended.size
    }

    override suspend fun deleteExpired(now: Instant): Int = synchronized(this) {
        val ended = sessions.values.filter { !it.expiresAt.isAfter(now) }
        ended.forEach { sessions.remove(it.tokenHash) }
        ended.size
    }
}

/**
 * Hashes nobody would ship: `fake:` and the password, so tests run fast and can read what was stored.
 * A hash starting with `old:` counts as outdated. [verifications] counts every check, decoys included.
 */
class FakePasswordHasher : PasswordHasher {
    var verifications = 0
        private set

    override suspend fun hash(password: String): String = "fake:$password"

    override suspend fun verify(password: String, encoded: String): Boolean {
        verifications++
        return encoded.substringAfter(':') == password && (encoded.startsWith("fake:") || encoded.startsWith("old:"))
    }

    override fun isOutdated(encoded: String): Boolean = encoded.startsWith("old:")
}
