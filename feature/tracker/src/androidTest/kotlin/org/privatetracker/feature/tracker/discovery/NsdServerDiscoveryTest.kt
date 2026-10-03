package org.privatetracker.feature.tracker.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.privatetracker.core.protocol.v1.LanAnnouncement
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone's own DNS-SD: a service announced as the server does it is found and resolved. */
@RunWith(AndroidJUnit4::class)
class NsdServerDiscoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val registered = CountDownLatch(1)
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = registered.countDown()
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    @After
    fun tearDown() {
        runCatching { nsd.unregisterService(registration) }
    }

    @Test
    fun aServerAnnouncedOnThisPhoneIsFoundWithItsPortAndKeyHint() = runBlocking {
        val port = 40_000 + (System.nanoTime() % 1000).toInt()
        val info = NsdServiceInfo().apply {
            serviceName = "PrivateTracker test $port"
            serviceType = LanAnnouncement.SERVICE_TYPE
            setPort(port)
            setAttribute(LanAnnouncement.PROTOCOL_ATTRIBUTE, "1")
            setAttribute(LanAnnouncement.KEY_HINT_ATTRIBUTE, "39400CB98F3ADB0E")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        assertTrue("registration", registered.await(10, TimeUnit.SECONDS))

        val found = NsdServerDiscovery(context).discover(Duration.ofSeconds(8))

        assertTrue(found.toString(), found.any { it.url.endsWith(":$port") && it.keyHint == "39400CB98F3ADB0E" })
    }
}
