package org.privatetracker.core.domain.usecase.common

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.PermissionReport
import org.privatetracker.core.domain.model.PermissionStatus
import org.privatetracker.core.domain.model.Requirement
import org.privatetracker.core.domain.network.loopbackUrl
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.repository.TrackerConfigRepository
import org.privatetracker.core.domain.usecase.tracker.StopTracking

/** The chosen mode; null sends the user to onboarding. */
class ObserveAppMode(private val appModes: AppModeRepository) {
    operator fun invoke(): Flow<AppMode?> = appModes.observe()
}

/** Saves the mode and stops the service of the role being turned off. */
class SetAppMode(
    private val appModes: AppModeRepository,
    private val trackerConfig: TrackerConfigRepository,
    private val serverConfig: ServerConfigRepository,
    private val stopTracking: StopTracking,
    private val serverController: ServerController,
) {
    suspend operator fun invoke(mode: AppMode) {
        appModes.set(mode)
        if (mode == AppMode.TRACKER_AND_SERVER) {
            // The tracker reports to the server on its own phone unless the user chose another.
            val port = serverConfig.get().port
            trackerConfig.update { if (it.serverUrl.isBlank()) it.copy(serverUrl = loopbackUrl(port)) else it }
        }
        if (!mode.tracks) stopTracking()
        if (!mode.serves) serverController.stop()
    }
}

/**
 * What [mode] needs on this phone. Required permissions block starting a service; recommended ones
 * cover cases such as a reboot or a server on the local network.
 */
class CheckPermissions(private val checker: PermissionChecker) {
    operator fun invoke(mode: AppMode): PermissionReport {
        val needs = mutableMapOf<AppPermission, Requirement>()
        fun need(permission: AppPermission, requirement: Requirement) {
            if (needs[permission] != Requirement.REQUIRED) needs[permission] = requirement
        }
        if (mode.tracks) {
            need(AppPermission.PRECISE_LOCATION, Requirement.REQUIRED)
            need(AppPermission.NOTIFICATIONS, Requirement.REQUIRED)
            need(AppPermission.BACKGROUND_LOCATION, Requirement.RECOMMENDED)
            need(AppPermission.LOCAL_NETWORK, Requirement.RECOMMENDED)
        }
        if (mode.serves) {
            need(AppPermission.LOCAL_NETWORK, Requirement.REQUIRED)
            need(AppPermission.NOTIFICATIONS, Requirement.RECOMMENDED)
        }
        need(AppPermission.BATTERY_OPTIMIZATION_EXEMPTION, Requirement.RECOMMENDED)

        return PermissionReport(
            needs.filterKeys(checker::isApplicable)
                .map { (permission, requirement) -> PermissionStatus(permission, requirement, checker.isGranted(permission)) }
                .sortedBy { it.permission.ordinal },
        )
    }
}
