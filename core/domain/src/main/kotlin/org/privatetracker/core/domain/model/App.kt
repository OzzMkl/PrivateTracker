package org.privatetracker.core.domain.model

import org.privatetracker.core.common.result.DomainError

/** Which roles this phone plays. Both can share one phone; its tracker then sends to 127.0.0.1. */
enum class AppMode {
    TRACKER, SERVER, TRACKER_AND_SERVER;

    val tracks: Boolean get() = this != SERVER
    val serves: Boolean get() = this != TRACKER
}

/** Platform permissions and settings the app depends on. Not every one exists on every Android version. */
enum class AppPermission {
    /** Precise location while the app or its foreground service is in use. */
    PRECISE_LOCATION,

    /** "Allow all the time": needed to start tracking from the background, as after a reboot. */
    BACKGROUND_LOCATION,

    /** Android 13+: the ongoing notifications that tell the tracked person that tracking is on. */
    NOTIFICATIONS,

    /** Android 17+: reaching local network addresses and accepting connections from them. */
    LOCAL_NETWORK,

    /** Exemption from battery optimizations, which the user grants in system settings. */
    BATTERY_OPTIMIZATION_EXEMPTION,
}

enum class Requirement { REQUIRED, RECOMMENDED }

data class PermissionStatus(val permission: AppPermission, val requirement: Requirement, val granted: Boolean)

/** The permissions a mode needs on this phone; those the Android version lacks are left out. */
data class PermissionReport(val items: List<PermissionStatus>) {
    val missingRequired: Set<AppPermission>
        get() = items.filter { it.requirement == Requirement.REQUIRED && !it.granted }.mapTo(mutableSetOf()) { it.permission }

    val allRequiredGranted: Boolean get() = missingRequired.isEmpty()
}

/** Inverse of building [DomainError.Permission] from permission names; unknown names are ignored. */
fun DomainError.Permission.permissions(): Set<AppPermission> =
    missing.mapNotNullTo(mutableSetOf()) { name -> AppPermission.entries.firstOrNull { it.name == name } }

fun permissionError(missing: Collection<AppPermission>): DomainError.Permission =
    DomainError.Permission(missing.mapTo(mutableSetOf()) { it.name })
