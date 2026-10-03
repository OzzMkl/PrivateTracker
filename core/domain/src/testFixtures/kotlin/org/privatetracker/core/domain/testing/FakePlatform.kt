package org.privatetracker.core.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.DiscoveredServer
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.model.ServerActivity
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.TrackerActivity
import org.privatetracker.core.domain.port.NetworkInfoProvider
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.domain.port.ServerDiscovery
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.port.TrackingController
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.core.domain.repository.AppModeRepository
import java.time.Duration

/** Every permission applies and is granted unless a test says otherwise. */
class FakePermissionChecker(
    val granted: MutableSet<AppPermission> = AppPermission.entries.toMutableSet(),
    val applicable: MutableSet<AppPermission> = AppPermission.entries.toMutableSet(),
) : PermissionChecker {
    override fun isApplicable(permission: AppPermission): Boolean = permission in applicable
    override fun isGranted(permission: AppPermission): Boolean = permission in granted
}

/** Starting marks the tracker as running at once, as the real service reports shortly after. */
class FakeTrackingController : TrackingController {
    override val activity = MutableStateFlow(TrackerActivity())
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
        activity.update { it.copy(running = true) }
    }

    override fun stop() {
        stops++
        activity.update { it.copy(running = false) }
    }
}

class FakeServerController(private val port: Int = 8787) : ServerController {
    override val activity = MutableStateFlow(ServerActivity())
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
        activity.value = ServerActivity(ServerRunState.Running(port))
    }

    override fun stop() {
        stops++
        activity.value = ServerActivity(ServerRunState.Stopped)
    }
}

class RecordingUploadScheduler : UploadScheduler {
    val scheduled = mutableListOf<Duration>()

    override fun scheduleUpload(delay: Duration) {
        scheduled += delay
    }
}

class FakeNetworkInfoProvider(addresses: List<NetworkAddress> = emptyList()) : NetworkInfoProvider {
    val addresses = MutableStateFlow(addresses)

    override fun observeAddresses(): Flow<List<NetworkAddress>> = addresses
}

class InMemoryAppModeRepository(initial: AppMode? = null) : AppModeRepository {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<AppMode?> = state
    override suspend fun get(): AppMode? = state.value
    override suspend fun set(mode: AppMode) {
        state.value = mode
    }
}

/** Servers announced on a pretend local network; [calls] counts the scans. */
class FakeServerDiscovery : ServerDiscovery {
    val found = mutableListOf<DiscoveredServer>()
    var calls = 0

    override suspend fun discover(timeout: Duration): List<DiscoveredServer> {
        calls++
        return found.toList()
    }
}
