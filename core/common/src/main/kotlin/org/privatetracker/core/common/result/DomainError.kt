package org.privatetracker.core.common.result

/**
 * Closed set of expected failures that cross layer boundaries.
 * [code] is stable: it is stored in the outbox, written to logs and compared in tests.
 */
sealed interface DomainError {
    val code: String

    data class Validation(val violations: List<FieldViolation>) : DomainError {
        override val code: String get() = "VALIDATION_FAILED"

        constructor(field: String, rule: String) : this(listOf(FieldViolation(field, rule)))
    }

    sealed interface Network : DomainError {
        data object Unreachable : Network {
            override val code: String = "NETWORK_UNREACHABLE"
        }

        data object Timeout : Network {
            override val code: String = "NETWORK_TIMEOUT"
        }

        data class InvalidResponse(val detail: String) : Network {
            override val code: String get() = "INVALID_RESPONSE"
        }
    }

    /** The remote server answered with an error status that has no more specific mapping. */
    data class Http(
        val status: Int,
        val serverCode: String? = null,
        val retryAfterSeconds: Long? = null,
    ) : DomainError {
        override val code: String get() = "HTTP_$status"
    }

    /** The tracker has no server URL yet. */
    data object NotConfigured : DomainError {
        override val code: String = "NOT_CONFIGURED"
    }

    data object DeviceNotRegistered : DomainError {
        override val code: String = "DEVICE_NOT_REGISTERED"
    }

    data object DeviceNotFound : DomainError {
        override val code: String = "DEVICE_NOT_FOUND"
    }

    data object NewDevicesDisabled : DomainError {
        override val code: String = "NEW_DEVICES_DISABLED"
    }

    /** The server knows the device and verified its signature, but nobody has approved it yet. */
    data object DevicePendingApproval : DomainError {
        override val code: String = "DEVICE_PENDING_APPROVAL"
    }

    /** The server's owner rejected or revoked this device. */
    data object DeviceRejected : DomainError {
        override val code: String = "DEVICE_REJECTED"
    }

    /** This phone's key store could not create or use the device key, so nothing can be signed. */
    data object DeviceKeyUnavailable : DomainError {
        override val code: String = "DEVICE_KEY_UNAVAILABLE"
    }

    /** The QR invite's ticket is unknown, expired, already used by another phone, or the proof is wrong. */
    data object PairingInvalid : DomainError {
        override val code: String = "PAIRING_INVALID"
    }

    /** The server at that address could not prove it holds the key this tracker was paired with. */
    data object ServerIdentityMismatch : DomainError {
        override val code: String = "SERVER_IDENTITY_MISMATCH"
    }

    /** The server could not attribute the request to the device it claims to come from. */
    data class AuthenticationFailed(val reason: AuthFailure) : DomainError {
        override val code: String get() = reason.code
    }

    data class UnsupportedProtocolVersion(val requested: Int, val supported: Int) : DomainError {
        override val code: String get() = "UNSUPPORTED_PROTOCOL_VERSION"
    }

    data class BatchTooLarge(val maxSize: Int) : DomainError {
        override val code: String get() = "BATCH_TOO_LARGE"
    }

    /** [missing] holds permission names as the domain defines them, so this module needs no Android types. */
    data class Permission(val missing: Set<String>) : DomainError {
        override val code: String get() = "PERMISSION_MISSING"
    }

    /** The embedded server could not listen on [port], usually because another app holds it. */
    data class PortInUse(val port: Int) : DomainError {
        override val code: String get() = "PORT_IN_USE"
    }

    data class ServerStartFailed(val detail: String) : DomainError {
        override val code: String get() = "SERVER_START_FAILED"
    }
}

/** One broken rule of one input field. [rule] is one of the constants below. */
data class FieldViolation(val field: String, val rule: String) {
    companion object {
        const val REQUIRED = "required"
        const val OUT_OF_RANGE = "out_of_range"
        const val INVALID_FORMAT = "invalid_format"
        const val TOO_LONG = "too_long"
    }
}

/** Why a signed request was refused. [code] is also the wire error code. */
enum class AuthFailure(val code: String) {
    /** No signature at all, as from a 0.1 tracker. */
    MISSING("SIGNATURE_MISSING"),
    MALFORMED("SIGNATURE_MALFORMED"),

    /** Signed too long ago or too far ahead: one of the clocks is wrong. */
    EXPIRED("SIGNATURE_EXPIRED"),
    REPLAYED("SIGNATURE_REPLAYED"),
    INVALID("SIGNATURE_INVALID"),

    /** The device id is registered with another key, as when the app's data moved to a new phone without its key. */
    KEY_MISMATCH("KEY_MISMATCH"),
}
