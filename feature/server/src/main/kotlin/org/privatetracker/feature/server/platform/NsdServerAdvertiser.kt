package org.privatetracker.feature.server.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.privatetracker.core.protocol.v1.ApiV1
import org.privatetracker.core.protocol.v1.LanAnnouncement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Announces the running server on the local network with Android's DNS-SD, so paired trackers find
 * it again after its address changes. Android renames the service if another one has the same name.
 */
@Singleton
class NsdServerAdvertiser @Inject constructor(@ApplicationContext context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(name: String, port: Int, keyHint: String?) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = LanAnnouncement.SERVICE_TYPE
            setPort(port)
            setAttribute(LanAnnouncement.PROTOCOL_ATTRIBUTE, ApiV1.PROTOCOL_VERSION.toString())
            keyHint?.let { setAttribute(LanAnnouncement.KEY_HINT_ATTRIBUTE, it) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Announced as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Announcement failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        // Throws if the registration already failed and the listener is unknown to the system.
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private companion object {
        const val TAG = "NsdServerAdvertiser"
    }
}
