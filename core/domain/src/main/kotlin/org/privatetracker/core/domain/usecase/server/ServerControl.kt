package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.privatetracker.core.common.result.Outcome
import org.privatetracker.core.common.result.asFailure
import org.privatetracker.core.common.result.asSuccess
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.ServerStatus
import org.privatetracker.core.domain.model.permissionError
import org.privatetracker.core.domain.network.serverAddresses
import org.privatetracker.core.domain.port.NetworkInfoProvider
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.domain.port.ServerController
import org.privatetracker.core.domain.repository.AppModeRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository

/** Starts the server service. Without local network access (Android 17) no tracker could reach it. */
class StartServer(
    private val checker: PermissionChecker,
    private val controller: ServerController,
) {
    operator fun invoke(): Outcome<Unit> {
        val permission = AppPermission.LOCAL_NETWORK
        if (checker.isApplicable(permission) && !checker.isGranted(permission)) {
            return permissionError(listOf(permission)).asFailure()
        }
        controller.start()
        return Unit.asSuccess()
    }
}

class StopServer(private val controller: ServerController) {
    operator fun invoke() = controller.stop()
}

/** At boot: starts the server if this phone serves and the user turned on autostart. Returns whether it started. */
class RestoreServer(
    private val appModes: AppModeRepository,
    private val serverConfig: ServerConfigRepository,
    private val startServer: StartServer,
) {
    suspend operator fun invoke(): Boolean {
        if (appModes.get()?.serves != true || !serverConfig.get().autoStart) return false
        return startServer() is Outcome.Success
    }
}

/** State, settings and reachable URLs of the server. URLs use the port it runs on, which a pending restart may change. */
class ObserveServerStatus(
    private val controller: ServerController,
    private val serverConfig: ServerConfigRepository,
    private val network: NetworkInfoProvider,
) {
    operator fun invoke(): Flow<ServerStatus> =
        combine(controller.activity, serverConfig.observe(), network.observeAddresses()) { activity, config, addresses ->
            val port = (activity.state as? ServerRunState.Running)?.port
            ServerStatus(
                activity = activity,
                config = config,
                addresses = if (port == null) emptyList() else serverAddresses(addresses, port, config.bindAddress),
            )
        }
}
