package org.privatetracker.feature.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.privatetracker.core.designsystem.component.StatusTone
import org.privatetracker.core.domain.model.Device
import org.privatetracker.core.domain.model.DeviceApproval
import org.privatetracker.core.domain.model.DeviceStatus
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.map.MarkerStyle
import org.privatetracker.core.designsystem.R as DesignR

internal fun DeviceStatus.tone(): StatusTone = when (this) {
    DeviceStatus.ONLINE -> StatusTone.POSITIVE
    DeviceStatus.STALE -> StatusTone.WARNING
    DeviceStatus.OFFLINE -> StatusTone.NEUTRAL
}

internal fun DeviceStatus.markerStyle(): MarkerStyle = when (this) {
    DeviceStatus.ONLINE -> MarkerStyle.ONLINE
    DeviceStatus.STALE -> MarkerStyle.STALE
    DeviceStatus.OFFLINE -> MarkerStyle.OFFLINE
}

@Composable
internal fun DeviceStatus.label(): String = stringResource(
    when (this) {
        DeviceStatus.ONLINE -> DesignR.string.status_online
        DeviceStatus.STALE -> DesignR.string.status_stale
        DeviceStatus.OFFLINE -> DesignR.string.status_offline
    },
)

internal fun DeviceApproval.tone(): StatusTone = when (this) {
    DeviceApproval.PENDING -> StatusTone.WARNING
    DeviceApproval.APPROVED -> StatusTone.POSITIVE
    DeviceApproval.REJECTED -> StatusTone.NEGATIVE
}

@Composable
internal fun DeviceApproval.label(): String = stringResource(
    when (this) {
        DeviceApproval.PENDING -> DesignR.string.approval_pending
        DeviceApproval.APPROVED -> DesignR.string.approval_approved
        DeviceApproval.REJECTED -> DesignR.string.approval_rejected
    },
)

/** The fingerprint to compare with the tracker's own screen, or a dash for a device without a key. */
internal fun Device.fingerprint(): String = publicKey?.let(::keyFingerprint) ?: "—"
