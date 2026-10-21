package org.privatetracker.feature.server.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.RelayState
import org.privatetracker.core.domain.model.ServerRunState
import org.privatetracker.core.domain.model.keyFingerprint
import org.privatetracker.core.domain.model.serverKeyHint
import org.privatetracker.core.domain.port.DeviceKeyException
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.core.domain.usecase.server.SyncWithPeers
import org.privatetracker.core.security.KeystoreServerKeys
import org.privatetracker.feature.server.R
import org.privatetracker.feature.server.host.NettyServerHost
import org.privatetracker.feature.server.platform.NsdServerAdvertiser
import org.privatetracker.feature.server.worker.MaintenanceScheduler
import org.privatetracker.server.tunnel.RelayTunnel
import org.privatetracker.server.tunnel.TunnelLog
import org.privatetracker.server.tunnel.TunnelTarget
import org.privatetracker.server.tunnel.TunnelVisitors
import java.net.InetSocketAddress
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
    @Inject lateinit var keystoreKeys: KeystoreServerKeys
    @Inject lateinit var tunnelVisitors: TunnelVisitors
    @Inject lateinit var syncWithPeers: SyncWithPeers

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var announcing: Job? = null
    private var relaying: Job? = null
    private var syncing: Job? = null

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
                    relay(state.port, config.bindAddress)
                    syncPeers()
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

    /**
     * Keeps a tunnel to the relay of the settings, from 1.0, so trackers reach this phone from anywhere;
     * a change of relay in the settings applies at once. Trackers' connections arrive at this phone's
     * own port, TLS untouched, on the address the server runs on: a new one in the settings waits for
     * the restart the screen asks for, as the server does.
     */
    private fun relay(port: Int, bindAddress: String) {
        relaying?.cancel()
        relaying = scope.launch {
            serverConfig.observe()
                .map { it.relay to it.relayFingerprint }
                .distinctUntilChanged()
                .collectLatest { (relay, relayFingerprint) ->
                    if (relay == null) {
                        controller.updateRelay(RelayState.Off)
                        return@collectLatest
                    }
                    val tunnel = try {
                        RelayTunnel(
                            relay = relay,
                            relayFingerprint = relayFingerprint,
                            serverFingerprint = checkNotNull(keyFingerprint(serverKeys.publicKey())),
                            keyManager = keystoreKeys.tlsKeyManager(),
                            targets = mapOf(TunnelTarget.API to InetSocketAddress(if (bindAddress == "0.0.0.0") "127.0.0.1" else bindAddress, port)),
                            log = AndroidTunnelLog,
                            visitors = tunnelVisitors,
                        )
                    } catch (e: DeviceKeyException) {
                        Log.e(TAG, "Server key unavailable; no relay", e)
                        controller.updateRelay(RelayState.Unreachable(e.message ?: "server key unavailable"))
                        return@collectLatest
                    }
                    coroutineScope {
                        tunnel.start(this)
                        tunnel.status.collect(controller::updateRelay)
                    }
                }
        }
    }

    /**
     * Exchanges with the paired servers, from 1.0: looked at every few seconds while the server runs;
     * each still gets about one exchange a minute, whichever side starts it.
     */
    private fun syncPeers() {
        syncing?.cancel()
        syncing = scope.launch {
            while (true) {
                delay(PEER_CHECK_MILLIS)
                try {
                    syncWithPeers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Exchange with paired servers failed", e)
                }
            }
        }
    }

    override fun onDestroy() {
        syncing?.cancel()
        relaying?.cancel()
        controller.updateRelay(RelayState.Off)
        scope.cancel()
        advertiser.stop()
        host.scope.launch { host.stop() }
        maintenance.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private object AndroidTunnelLog : TunnelLog {
        override fun info(message: String) {
            Log.i(TAG, message)
        }

        override fun warn(message: String, error: Throwable?) {
            Log.w(TAG, message, error)
        }
    }

    private companion object {
        const val TAG = "ServerRelay"
        const val PEER_CHECK_MILLIS = 15_000L
    }
}
