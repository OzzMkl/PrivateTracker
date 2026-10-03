package org.privatetracker.feature.tracker.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.privatetracker.core.domain.model.DiscoveredServer
import org.privatetracker.core.domain.port.ServerDiscovery
import org.privatetracker.core.protocol.v1.LanAnnouncement
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Listens for servers announced with DNS-SD for a few seconds, resolving each one in turn. Before
 * Android 14 only one resolution may run at a time, and a second one fails; one at a time suits both.
 * IPv4 only: the URLs trackers use elsewhere are IPv4 too, and IPv6 link-local needs an interface scope.
 */
class NsdServerDiscovery @Inject constructor(@ApplicationContext context: Context) : ServerDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    override suspend fun discover(timeout: Duration): List<DiscoveredServer> {
        val found = mutableListOf<DiscoveredServer>()
        withTimeoutOrNull(timeout.toMillis()) {
            callbackFlow {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(service: NsdServiceInfo) {
                        trySend(service)
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        close()
                    }

                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onServiceLost(service: NsdServiceInfo) = Unit
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }
                nsd.discoverServices(LanAnnouncement.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
            }.collect { service -> resolve(service)?.let { synchronized(found) { found += it } } }
        }
        return synchronized(found) { found.distinct() }
    }

    private suspend fun resolve(service: NsdServiceInfo): List<DiscoveredServer>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(service) else resolveLegacy(service)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveWithCallback(service: NsdServiceInfo): List<DiscoveredServer>? = suspendCancellableCoroutine { continuation ->
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(servers(info, info.hostAddresses))
                runCatching { nsd.unregisterServiceInfoCallback(this) }
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(service, executor, callback)
        continuation.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(service: NsdServiceInfo): List<DiscoveredServer>? = suspendCancellableCoroutine { continuation ->
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(servers(info, listOfNotNull(info.host)))
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    private fun servers(info: NsdServiceInfo, hosts: List<InetAddress>): List<DiscoveredServer> {
        val keyHint = info.attributes[LanAnnouncement.KEY_HINT_ATTRIBUTE]?.decodeToString()
        return hosts.filterIsInstance<Inet4Address>().map { DiscoveredServer("http://${it.hostAddress}:${info.port}", keyHint) }
    }
}
