package org.privatetracker.core.domain.usecase.tracker

import java.net.URI
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.TrackingStatus
import org.privatetracker.core.domain.model.permissionError
import org.privatetracker.core.domain.network.needsLocalNetworkAccess
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.domain.port.TrackingController
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.OutboxRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.common.TrustOwnServer
import org.privatetracker.core.domain.validation.TrackerConfigValidator

/**
 * Permissions tracking cannot run without. A start from the background, as at boot, also needs
 * location "all the time"; a server on the local network needs Android 17's local network access.
 */
internal fun missingTrackingPermissions(
    config: TrackerConfig,
    checker: PermissionChecker,
    fromBackground: Boolean,
): List<AppPermission> = buildList {
    add(AppPermission.PRECISE_LOCATION)
    add(AppPermission.NOTIFICATIONS)
    if (fromBackground) add(AppPermission.BACKGROUND_LOCATION)
    val host = runCatching { URI(config.serverUrl.trim()).host }.getOrNull()
    if (host != null && needsLocalNetworkAccess(host)) add(AppPermission.LOCAL_NETWORK)
}.filter { checker.isApplicable(it) && !checker.isGranted(it) }

/** Checks configuration and permissions, records that the user wants tracking on, and starts the service. */
class StartTracking(
    private val trackerConfig: TrackerConfigRepository,
    private val checker: PermissionChecker,
    private val controller: TrackingController,
) {
    suspend operator fun invoke(): Outcome<Unit> {
        val config = trackerConfig.get()
        if (config.serverUrl.isBlank()) return DomainError.NotConfigured.asFailure()
        if (config.serverPin == null) return DomainError.ServerNotTrusted.asFailure()
        val violations = TrackerConfigValidator.validate(config)
        if (violations.isNotEmpty()) return DomainError.Validation(violations).asFailure()
        val missing = missingTrackingPermissions(config, checker, fromBackground = false)
        if (missing.isNotEmpty()) return permissionError(missing).asFailure()

        trackerConfig.update { it.copy(trackingEnabled = true) }
        controller.start()
        return Unit.asSuccess()
    }
}

/** Stops the service and leaves whatever is still queued to a background upload. */
class StopTracking(
    private val trackerConfig: TrackerConfigRepository,
    private val controller: TrackingController,
    private val uploadScheduler: UploadScheduler,
) {
    suspend operator fun invoke() {
        trackerConfig.update { it.copy(trackingEnabled = false) }
        controller.stop()
        uploadScheduler.scheduleUpload(Duration.ZERO)
    }
}

enum class RestoreTrigger { BOOT, APP_OPENED }

enum class RestoreResult { STARTED, ALREADY_RUNNING, NOT_WANTED, NOT_CONFIGURED, MISSING_PERMISSIONS }

/**
 * Starts tracking again after a reboot or after the process died, but only if the user still wants it.
 * At boot it also needs [TrackerConfig.startOnBoot] and location "all the time". A tracker on the
 * server's own phone first trusts that server's key; see [TrustOwnServer].
 */
class RestoreTracking(
    private val appModes: AppModeRepository,
    private val trackerConfig: TrackerConfigRepository,
    private val checker: PermissionChecker,
    private val controller: TrackingController,
    private val trustOwnServer: TrustOwnServer,
) {
    suspend operator fun invoke(trigger: RestoreTrigger): RestoreResult {
        trustOwnServer()
        val config = trackerConfig.get()
        if (appModes.get()?.tracks != true || !config.trackingEnabled) return RestoreResult.NOT_WANTED
        if (trigger == RestoreTrigger.BOOT && !config.startOnBoot) return RestoreResult.NOT_WANTED
        if (controller.activity.value.running) return RestoreResult.ALREADY_RUNNING
        if (TrackerConfigValidator.validate(config).isNotEmpty()) return RestoreResult.NOT_CONFIGURED
        if (missingTrackingPermissions(config, checker, fromBackground = trigger == RestoreTrigger.BOOT).isNotEmpty()) {
            return RestoreResult.MISSING_PERMISSIONS
        }
        controller.start()
        return RestoreResult.STARTED
    }
}

class ObserveTrackingStatus(
    private val trackerConfig: TrackerConfigRepository,
    private val controller: TrackingController,
    private val outbox: OutboxRepository,
) {
    operator fun invoke(): Flow<TrackingStatus> =
        combine(trackerConfig.observe(), controller.activity, outbox.observeCount(), ::TrackingStatus)
}
