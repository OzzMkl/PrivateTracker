package org.privatetracker.server.linux.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the command line asks the running server over its admin socket: one JSON line per request,
 * answered with one JSON line, or with several for [Pair] (the invite, then how it ended).
 * Both ends are the same program, so the format only has to match itself.
 */
@Serializable
sealed interface AdminRequest {
    @Serializable @SerialName("status")
    data object Status : AdminRequest

    @Serializable @SerialName("devices")
    data object Devices : AdminRequest

    /** [device]: a device id, the start of one (4 characters or more), or an exact name. */
    @Serializable @SerialName("approve")
    data class Approve(val device: String? = null, val allPending: Boolean = false) : AdminRequest

    @Serializable @SerialName("reject")
    data class Reject(val device: String) : AdminRequest

    @Serializable @SerialName("remove")
    data class Remove(val device: String) : AdminRequest

    @Serializable @SerialName("rotate-key")
    data object RotateKey : AdminRequest

    /** Keeps the connection open until a tracker uses the invite or it expires; closing it withdraws the invite. */
    @Serializable @SerialName("pair")
    data object Pair : AdminRequest

    // The web app's accounts (1.0). Passwords cross only this socket, which only the server's user can open.

    @Serializable @SerialName("users")
    data object Users : AdminRequest

    @Serializable @SerialName("add-user")
    data class AddUser(val name: String, val password: String) : AdminRequest {
        // Requests are logged when they fail; the password never is.
        override fun toString() = "AddUser(name=$name)"
    }

    /** A new password signs the account out everywhere. */
    @Serializable @SerialName("set-password")
    data class SetPassword(val name: String, val password: String) : AdminRequest {
        override fun toString() = "SetPassword(name=$name)"
    }

    @Serializable @SerialName("remove-user")
    data class RemoveUser(val name: String) : AdminRequest

    // Paired servers (1.0).

    @Serializable @SerialName("peers")
    data object Peers : AdminRequest

    /** Like [Pair]: the invite, then how it ended, on one connection; closing it withdraws the invite. */
    @Serializable @SerialName("peer-invite")
    data object PeerInvite : AdminRequest

    /** [link]: the invite another server showed, `privatetracker://peer?...`. */
    @Serializable @SerialName("peer-join")
    data class PeerJoin(val link: String) : AdminRequest

    /** [peer]: a paired server's exact name or its fingerprint. */
    @Serializable @SerialName("peer-remove")
    data class PeerRemove(val peer: String) : AdminRequest
}

@Serializable
sealed interface AdminReply {
    @Serializable @SerialName("error")
    data class Error(val message: String) : AdminReply

    @Serializable @SerialName("status")
    data class Status(
        val serverName: String,
        val version: String,
        val port: Int,
        val startedAtMillis: Long,
        val requests: Long,
        val fingerprint: String?,
        val inviteUrls: List<String>,
        val devices: Int,
        val pending: Int,
        val online: Int,
        val encryptionKeyId: String?,
        val encryptionKeyUntilMillis: Long?,
        /** Null while the web app is off. */
        val webPort: Int? = null,
        val webCertificateSha256: String? = null,
        val webAccounts: Int = 0,
        /** Null without a relay. */
        val relay: String? = null,
        val relayState: String? = null,
        val relayUrl: String? = null,
        val peers: Int = 0,
    ) : AdminReply

    @Serializable @SerialName("devices")
    data class Devices(val devices: List<DeviceRow>) : AdminReply

    @Serializable @SerialName("done")
    data class Done(val devices: List<DeviceRow> = emptyList(), val encryptionKeyId: String? = null) : AdminReply

    @Serializable @SerialName("invite")
    data class Invite(val uri: String, val urls: List<String>, val fingerprint: String?, val expiresAtMillis: Long) : AdminReply

    @Serializable @SerialName("paired")
    data class Paired(val device: DeviceRow) : AdminReply

    @Serializable @SerialName("expired")
    data object Expired : AdminReply

    @Serializable @SerialName("users")
    data class Users(val users: List<UserRow>) : AdminReply

    @Serializable @SerialName("peers")
    data class Peers(val peers: List<PeerRow>) : AdminReply

    @Serializable @SerialName("peer-joined")
    data class PeerJoined(val peer: PeerRow) : AdminReply
}

@Serializable
data class PeerRow(
    val name: String,
    val fingerprint: String,
    val addresses: List<String>,
    val pairedAtMillis: Long,
    val lastExchangeAtMillis: Long?,
    val lastError: String?,
)

@Serializable
data class UserRow(val name: String, val createdAtMillis: Long)

@Serializable
data class DeviceRow(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String?,
    val approval: String,
    val status: String,
    val lastSeenAtMillis: Long?,
    val fingerprint: String?,
)

internal val AdminJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

/** A request line longer than this is not one the command line sends; a password of 256 characters fits. */
internal const val MAX_LINE_CHARS = 4_096
