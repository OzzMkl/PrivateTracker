package org.privatetracker.core.protocol.v1

import kotlinx.serialization.json.Json
import org.privatetracker.core.domain.model.ProtocolVersion
import org.privatetracker.core.domain.validation.MAX_BATCH_SIZE

/** Routes and limits of protocol v1. An incompatible change creates v2; adding fields does not. */
object ApiV1 {
    const val PROTOCOL_VERSION = ProtocolVersion.CURRENT
    const val BASE_PATH = "/api/v1"
    const val HEALTH = "$BASE_PATH/health"
    const val REGISTER = "$BASE_PATH/devices/register"
    const val DEVICES = "$BASE_PATH/devices"
    const val DEVICE_ID_PARAM = "device_id"
    /** Query parameter of [HEALTH]: a random challenge the server signs with its key. */
    const val CHALLENGE_PARAM = "challenge"
    const val DEVICE = "$DEVICES/{$DEVICE_ID_PARAM}"
    const val LOCATIONS = "$DEVICE/locations"

    const val MAX_BATCH = MAX_BATCH_SIZE
    const val MAX_BODY_BYTES = 256 * 1024
    const val PROBLEM_CONTENT_TYPE = "application/problem+json"

    fun devicePath(deviceId: String): String = "$DEVICES/$deviceId"
    fun locationsPath(deviceId: String): String = "$DEVICES/$deviceId/locations"
}

/**
 * How a server announces itself on the local network (DNS-SD over mDNS), from 0.3 on. The TXT record
 * carries the protocol version and a hint of the server's key, so a tracker skips servers that are
 * clearly not its own; only the signed health challenge proves which server it is.
 */
object LanAnnouncement {
    const val SERVICE_TYPE = "_privatetracker._tcp"
    const val PROTOCOL_ATTRIBUTE = "v"
    const val KEY_HINT_ATTRIBUTE = "k"
}

/** Wire JSON: unknown fields are ignored and nulls omitted, so either side can add optional fields. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Stable error codes carried in the `code` member of a problem response. */
enum class ErrorCode {
    MALFORMED_JSON,
    EMPTY_BATCH,
    VALIDATION_FAILED,
    UNSUPPORTED_PROTOCOL_VERSION,
    NEW_DEVICES_DISABLED,
    LOCAL_ONLY,
    DEVICE_NOT_REGISTERED,
    DEVICE_NOT_FOUND,
    NOT_FOUND,
    BATCH_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    RATE_LIMITED,
    INTERNAL_ERROR,
    SHUTTING_DOWN,

    // Request signatures, from 0.2 on. Each name equals the AuthFailure code it reports.
    SIGNATURE_MISSING,
    SIGNATURE_MALFORMED,
    SIGNATURE_EXPIRED,
    SIGNATURE_REPLAYED,
    SIGNATURE_INVALID,
    KEY_MISMATCH,
    DEVICE_PENDING_APPROVAL,
    DEVICE_REJECTED,

    // QR pairing, from 0.3 on.
    PAIRING_INVALID,

    // End-to-end encryption, from 0.5 on.
    ENCRYPTION_REQUIRED,
    ENCRYPTION_KEY_UNKNOWN,
    DECRYPTION_FAILED;

    /** RFC 9457 `type`: a URN, since the project owns no domain to host problem pages. */
    val problemType: String get() = "urn:privatetracker:problem:" + name.lowercase().replace('_', '-')

    companion object {
        fun parse(raw: String?): ErrorCode? = entries.firstOrNull { it.name == raw }
    }
}
