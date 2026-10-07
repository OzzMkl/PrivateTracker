package org.privatetracker.core.protocol.v1

import org.privatetracker.core.domain.model.PAIRING_SECRET_BYTES
import org.privatetracker.core.domain.model.PairingInvite
import org.privatetracker.core.domain.validation.TrackerConfigValidator
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

/**
 * The content of a pairing QR code, a link any camera app can hand to PrivateTracker:
 *
 *     privatetracker://pair?v=1&name=Casa&url=https%3A%2F%2F192.168.1.50%3A8787&url=...&key=<base64url>&ticket=..&secret=..&expires=<epoch s>
 *
 * Keys and secrets use Base64url so the QR stays small; [PairingInvite] keeps the protocol's Base64 key.
 */
object PairingUri {
    const val SCHEME = "privatetracker"
    const val HOST = "pair"
    private const val VERSION = "1"
    private val TOKEN = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun format(invite: PairingInvite): String {
        val parameters = buildList {
            add("v" to VERSION)
            add("name" to invite.serverName)
            invite.serverUrls.forEach { add("url" to it) }
            add("key" to Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(invite.serverKey)))
            add("ticket" to invite.ticketId)
            add("secret" to invite.secret)
            add("expires" to invite.expiresAt.epochSecond.toString())
        }
        return "$SCHEME://$HOST?" + parameters.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    }

    /** Null when [raw] is not a pairing invite this version understands. */
    fun parse(raw: String): PairingInvite? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val parameters = uri.rawQuery.orEmpty().split("&").filter { it.contains("=") }.map { part ->
            val name = part.substringBefore("=")
            name to runCatching { URLDecoder.decode(part.substringAfter("="), Charsets.UTF_8) }.getOrNull()
        }
        fun single(name: String): String? = parameters.singleOrNull { it.first == name }?.second
        if (single("v") != VERSION) return null

        val urls = parameters.filter { it.first == "url" }.mapNotNull { it.second }
            .filter { TrackerConfigValidator.validateServerUrl(it) == null }
            .map(TrackerConfigValidator::normalizeServerUrl)
        val key = single("key")?.let { runCatching { Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(it)) }.getOrNull() }
        val ticket = single("ticket")?.takeIf(TOKEN::matches)
        val secret = single("secret")?.takeIf { TOKEN.matches(it) && decodedSize(it) == PAIRING_SECRET_BYTES }
        val expires = single("expires")?.toLongOrNull()?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
        val name = single("name")?.trim()?.takeIf { it.isNotEmpty() }
        if (urls.isEmpty() || key == null || ticket == null || secret == null || expires == null || name == null) return null
        return PairingInvite(name, urls, key, ticket, secret, expires)
    }

    private fun decodedSize(token: String): Int = runCatching { Base64.getUrlDecoder().decode(token).size }.getOrDefault(-1)
}
