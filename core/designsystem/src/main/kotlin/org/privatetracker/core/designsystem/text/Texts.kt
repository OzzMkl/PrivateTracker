package org.privatetracker.core.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.privatetracker.core.common.result.AuthFailure
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.designsystem.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Texts shared by the features. Permission names arrive as strings, so this module needs no domain types.

@Composable
fun DomainError.message(): String = when (this) {
    is DomainError.Validation -> stringResource(R.string.error_validation)
    DomainError.Network.Unreachable -> stringResource(R.string.error_network_unreachable)
    DomainError.Network.Timeout -> stringResource(R.string.error_network_timeout)
    is DomainError.Network.InvalidResponse -> stringResource(R.string.error_invalid_response)
    is DomainError.Http -> stringResource(R.string.error_http, status)
    DomainError.NotConfigured -> stringResource(R.string.error_not_configured)
    DomainError.DeviceNotRegistered -> stringResource(R.string.error_device_not_registered)
    DomainError.DeviceNotFound -> stringResource(R.string.error_device_not_found)
    DomainError.NewDevicesDisabled -> stringResource(R.string.error_new_devices_disabled)
    DomainError.DevicePendingApproval -> stringResource(R.string.error_device_pending)
    DomainError.DeviceRejected -> stringResource(R.string.error_device_rejected)
    DomainError.DeviceKeyUnavailable -> stringResource(R.string.error_device_key)
    DomainError.PairingInvalid -> stringResource(R.string.error_pairing_invalid)
    DomainError.ServerIdentityMismatch -> stringResource(R.string.error_server_identity)
    is DomainError.AuthenticationFailed -> stringResource(
        when (reason) {
            AuthFailure.EXPIRED -> R.string.error_signature_expired
            AuthFailure.REPLAYED -> R.string.error_signature_replayed
            AuthFailure.KEY_MISMATCH -> R.string.error_key_mismatch
            AuthFailure.MISSING, AuthFailure.MALFORMED, AuthFailure.INVALID -> R.string.error_signature_invalid
        },
    )
    is DomainError.UnsupportedProtocolVersion -> stringResource(R.string.error_unsupported_protocol, supported)
    is DomainError.BatchTooLarge -> pluralStringResource(R.plurals.error_batch_too_large, maxSize, maxSize)
    is DomainError.Permission -> stringResource(R.string.error_permission, missing.map { permissionLabel(it) }.joinToString())
    is DomainError.PortInUse -> stringResource(R.string.error_port_in_use, port)
    is DomainError.ServerStartFailed -> stringResource(R.string.error_server_start_failed, detail)
}

/** Short name of a permission, from its domain name (AppPermission.name). */
@Composable
fun permissionLabel(name: String): String = when (name) {
    "PRECISE_LOCATION" -> stringResource(R.string.permission_precise_location)
    "BACKGROUND_LOCATION" -> stringResource(R.string.permission_background_location)
    "NOTIFICATIONS" -> stringResource(R.string.permission_notifications)
    "LOCAL_NETWORK" -> stringResource(R.string.permission_local_network)
    "BATTERY_OPTIMIZATION_EXEMPTION" -> stringResource(R.string.permission_battery)
    else -> name
}

@Composable
fun FieldViolation.message(): String = when (rule) {
    FieldViolation.REQUIRED -> stringResource(R.string.violation_required)
    FieldViolation.OUT_OF_RANGE -> stringResource(R.string.violation_out_of_range)
    FieldViolation.INVALID_FORMAT -> stringResource(R.string.violation_invalid_format)
    FieldViolation.TOO_LONG -> stringResource(R.string.violation_too_long)
    else -> rule
}

/**
 * "hace 5 min", from the app's own strings so it matches the rest of the screen whatever the phone's
 * language. Pass a [now] that ticks so the text stays current.
 */
@Composable
fun relativeTime(instant: Instant?, now: Instant): String {
    if (instant == null) return stringResource(R.string.never)
    val elapsed = Duration.between(instant, now)
    val minutes = elapsed.toMinutes().toInt()
    val hours = elapsed.toHours().toInt()
    val days = elapsed.toDays().toInt()
    return when {
        minutes < 1 -> stringResource(R.string.just_now)
        hours < 1 -> pluralStringResource(R.plurals.minutes_ago, minutes, minutes)
        days < 1 -> pluralStringResource(R.plurals.hours_ago, hours, hours)
        else -> pluralStringResource(R.plurals.days_ago, days, days)
    }
}

private val DateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

fun dateTime(instant: Instant): String = DateTimeFormat.format(instant.atZone(ZoneId.systemDefault()))

@Composable
fun coordinates(latitude: Double, longitude: Double): String = stringResource(R.string.coordinates, latitude, longitude)

@Composable
fun accuracy(meters: Float?): String = if (meters == null) stringResource(R.string.unknown) else stringResource(R.string.accuracy_meters, meters)
