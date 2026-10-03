package org.privatetracker.feature.server.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.serverKeyHint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.feature.server.R
import org.privatetracker.feature.server.host.NettyServerHost
import org.privatetracker.feature.server.platform.NsdServerAdvertiser
import org.privatetracker.feature.server.worker.MaintenanceScheduler
import javax.inject.Inject

/** Keeps the process alive while the server listens. A server that fails to start stops the service. */
@AndroidEntryPoint
class ServerService : Service() {
    @Inject lateinit var host: NettyServerHost
    @Inject lateinit var serverConfig: ServerConfigRepository
    @Inject lateinit var controller: ServiceServerController
    @Inject lateinit var maintenance: MaintenanceScheduler
    @Inject lateinit var advertiser: NsdServerAdvertiser
    @Inject lateinit var serverKeys: ServerKeys

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var announcing: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            ServerNotification.ID,
            ServerNotification.build(this, getString(R.string.server_notification_starting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        maintenance.schedule()
        host.scope.launch {
            val config = serverConfig.get()
            host.start(config.port, config.bindAddress)
            when (val state = controller.activity.value.state) {
                is ServerRunState.Running -> {
                    ServerNotification.show(this@ServerService, getString(R.string.server_notification_running, state.port))
                    announce(state.port)
                }
                is ServerRunState.Failed -> {
                    val error = state.error
                    val text = if (error is DomainError.PortInUse) {
                        getString(R.string.server_notification_port_in_use, error.port)
                    } else {
                        getString(R.string.server_notification_failed)
                    }
                    ServerNotification.show(this@ServerService, text)
                    stopSelf()
                }
                else -> Unit
            }
        }
        return START_STICKY
    }

    /** Follows the settings: turning the announcement off or renaming the server applies at once. */
    private fun announce(port: Int) {
        announcing?.cancel()
        announcing = scope.launch {
            val keyHint = try {
                serverKeyHint(serverKeys.publicKey())
            } catch (e: DeviceKeyException) {
                null
            }
            serverConfig.observe()
                .map { it.advertiseOnLan to it.serverName }
                .distinctUntilChanged()
                .collect { (enabled, name) -> if (enabled) advertiser.start(name, port, keyHint) else advertiser.stop() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        advertiser.stop()
        host.scope.launch { host.stop() }
        maintenance.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
