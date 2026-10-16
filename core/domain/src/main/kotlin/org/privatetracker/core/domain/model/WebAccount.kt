package org.privatetracker.core.domain.model

import java.time.Duration
import java.time.Instant

/**
 * Someone who signs in to the web app of a Linux server, from 1.0 on. Every account can do everything
 * the owner can; they exist so each person has a password of their own, not to limit anyone.
 * [name] is stored in lower case. [passwordHash] is what a [PasswordHasher][org.privatetracker.core.domain.port.PasswordHasher]
 * made, never the password.
 */
data class WebAccount(
    val name: String,
    val passwordHash: String,
    val createdAt: Instant,
    val passwordChangedAt: Instant,
) {
    companion object {
        const val NAME_MAX_LENGTH = 32

        /**
         * NIST SP 800-63B-4 asks for at least 15 characters when a password is the only factor, as here,
         * and for no rules about which kinds of characters it mixes.
         */
        const val PASSWORD_MIN_LENGTH = 15

        /** NIST asks to allow at least 64; longer only makes hashing a way to spend the server's time. */
        const val PASSWORD_MAX_LENGTH = 256
    }
}

/**
 * A browser signed in as [account]. The server keeps only [tokenHash], the SHA-256 of the token in the
 * browser's cookie, so a copy of the server's files signs nobody in. It ends at [expiresAt] however
 * often it is used, at sign-out, or when the account's password changes or the account goes.
 * [passwordSetAt] is the account's [WebAccount.passwordChangedAt] when the password was checked: a
 * session opened with a password that changed meanwhile, even a moment before it was stored, opens nothing.
 */
data class WebSession(
    val tokenHash: String,
    val account: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val passwordSetAt: Instant,
) {
    companion object {
        val LIFETIME: Duration = Duration.ofDays(30)
    }
}

/** A session just made, with the token the browser keeps; the server forgets [token] at once. */
class NewWebSession(val token: String, val session: WebSession)
