package org.privatetracker.feature.server.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.port.NetworkInfoProvider
import java.net.NetworkInterface
import javax.inject.Inject

/** Lists interface addresses again whenever any network, VPNs included, comes, goes or changes. */
class AndroidNetworkInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkInfoProvider {
    override fun observeAddresses(): Flow<List<NetworkAddress>> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(readAddresses())
            }

            override fun onLost(network: Network) {
                trySend(readAddresses())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(readAddresses())
            }
        }
        trySend(readAddresses())
        // A LAN without internet access still counts, and so do VPNs.
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun readAddresses(): List<NetworkAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList().mapNotNull { address ->
                    address.hostAddress?.substringBefore('%')?.let { NetworkAddress(networkInterface.name, it) }
                }
            }
    }.getOrDefault(emptyList())
}
