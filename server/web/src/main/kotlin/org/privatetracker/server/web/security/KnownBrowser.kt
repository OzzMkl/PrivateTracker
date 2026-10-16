package org.privatetracker.server.web.security

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import org.privatetracker.core.domain.model.WebAccount
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A browser an account signed in from before. Strangers guessing at an account's name use up its
 * sign-in attempts; a browser with this cookie still gets in with the right password (its own address
 * stays limited). The cookie is `<name>.<HMAC>` over the name and the time its password was set, with
 * a key only the server holds, so it cannot be forged and a new password retires every one of them.
 * It opens nothing by itself: the password is still checked.
 */
internal const val KNOWN_BROWSER_COOKIE = "__Host-pt-known"

/** As long as browsers keep a cookie: Chrome caps them at 400 days. */
private const val KNOWN_BROWSER_SECONDS = 400 * 24 * 3600

internal class KnownBrowsers(key: ByteArray) {
    private val key = SecretKeySpec(key.copyOf(), "HmacSHA256")

    fun value(account: WebAccount): String = "${account.name}.${mac(account)}"

    /** True when [cookie] is this server's for [account] and its current password. */
    fun isKnown(cookie: String?, account: WebAccount): Boolean {
        val expected = value(account)
        return cookie != null && MessageDigest.isEqual(cookie.encodeToByteArray(), expected.encodeToByteArray())
    }

    private fun mac(account: WebAccount): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val input = "PrivateTracker-known-browser\n${account.name}\n${account.passwordChangedAt.toEpochMilli()}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.encodeToByteArray()))
    }
}

internal fun ApplicationCall.knownBrowserCookie(): String? = request.cookies[KNOWN_BROWSER_COOKIE, CookieEncoding.RAW]

internal fun ApplicationCall.setKnownBrowserCookie(value: String) {
    response.cookies.append(
        Cookie(
            name = KNOWN_BROWSER_COOKIE,
            value = value,
            encoding = CookieEncoding.RAW,
            maxAge = KNOWN_BROWSER_SECONDS,
            path = "/",
            secure = true,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}
