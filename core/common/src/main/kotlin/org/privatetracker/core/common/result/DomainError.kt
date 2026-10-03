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

    data class UnsupportedProtocolVersion(val requested: Int, val supported: Int) : DomainError {
        override val code: String get() = "UNSUPPORTED_PROTOCOL_VERSION"
    }

    data class BatchTooLarge(val maxSize: Int) : DomainError {
        override val code: String get() = "BATCH_TOO_LARGE"
    }

    data class Permission(val missing: Set<String>) : DomainError {
        override val code: String get() = "PERMISSION_MISSING"
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
