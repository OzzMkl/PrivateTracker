package org.privatetracker.core.domain.usecase.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.AddressKind
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.NetworkAddress
import org.privatetracker.core.domain.model.ServerAddress
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.permissions
import org.privatetracker.core.domain.testing.FakeNetworkInfoProvider
import org.privatetracker.core.domain.testing.FakePermissionChecker
import org.privatetracker.core.domain.testing.FakeServerController
import org.privatetracker.core.domain.testing.InMemoryAppModeRepository
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerConfigRepository
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.successValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StartServerTest {
    private val permissions = FakePermissionChecker()
    private val controller = FakeServerController()
    private val start = StartServer(permissions, controller)

    @Test
    fun `the server starts when it can accept connections from the local network`() {
        start().successValue()
        assertEquals(1, controller.starts)
    }

    @Test
    fun `without local network access the server does not start`() {
        permissions.granted -= AppPermission.LOCAL_NETWORK

        val error = assertIs<DomainError.Permission>(start().failureError())

        assertEquals(setOf(AppPermission.LOCAL_NETWORK), error.permissions())
        assertEquals(0, controller.starts)
    }
}

class RestoreServerTest {
    private val controller = FakeServerController()

    private fun restore(mode: AppMode?, autoStart: Boolean) = RestoreServer(
        InMemoryAppModeRepository(mode),
        InMemoryServerConfigRepository(ServerConfig(autoStart = autoStart)),
        StartServer(FakePermissionChecker(), controller),
    )

    @Test
    fun `at boot the server starts only with autostart in a serving mode`() = runTest {
        assertTrue(restore(AppMode.SERVER, autoStart = true)())
        assertTrue(restore(AppMode.TRACKER_AND_SERVER, autoStart = true)())
        assertFalse(restore(AppMode.SERVER, autoStart = false)())
        assertFalse(restore(AppMode.TRACKER, autoStart = true)())
        assertFalse(restore(null, autoStart = true)())
        assertEquals(2, controller.starts)
    }
}

class ObserveServerStatusTest {
    private val controller = FakeServerController(port = 8787)
    private val network = FakeNetworkInfoProvider(
        listOf(
            NetworkAddress("lo", "127.0.0.1"),
            NetworkAddress("tun0", "100.101.102.103"),
            NetworkAddress("wlan0", "192.168.1.20"),
            NetworkAddress("wlan0", "fe80::1"),
        ),
    )
    private val config = InMemoryServerConfigRepository()
    private val observe = ObserveServerStatus(controller, config, network)

    @Test
    fun `a stopped server lists no addresses`() = runTest {
        assertTrue(observe().first().addresses.isEmpty())
    }

    @Test
    fun `a running server lists its ipv4 urls, local network first and loopback last`() = runTest {
        controller.start()

        assertEquals(
            listOf(
                ServerAddress("http://192.168.1.20:8787", AddressKind.LAN),
                ServerAddress("http://100.101.102.103:8787", AddressKind.VPN),
                ServerAddress("http://127.0.0.1:8787", AddressKind.LOOPBACK),
            ),
            observe().first().addresses,
        )
    }

    @Test
    fun `a server bound to one address is only reachable there`() = runTest {
        config.update { it.copy(bindAddress = "127.0.0.1") }
        controller.start()

        assertEquals(listOf("http://127.0.0.1:8787"), observe().first().addresses.map { it.url })
    }
}

class UpdateServerConfigTest {
    private val serverConfig = InMemoryServerConfigRepository(ServerConfig(port = 8787))
    private val trackerConfig = InMemoryTrackerConfigRepository(TrackerConfig(serverUrl = "http://127.0.0.1:8787"))
    private val update = UpdateServerConfig(serverConfig, trackerConfig)

    @Test
    fun `a new port needs a restart and moves the tracker on the same phone along`() = runTest {
        val result = update(ServerConfig(port = 9000)).successValue()

        assertTrue(result.restartRequired)
        assertEquals(9000, serverConfig.get().port)
        assertEquals("http://127.0.0.1:9000", trackerConfig.get().serverUrl)
    }

    @Test
    fun `a tracker reporting to another server keeps its url`() = runTest {
        trackerConfig.update { it.copy(serverUrl = "http://192.168.1.10:8787") }

        update(ServerConfig(port = 9000)).successValue()

        assertEquals("http://192.168.1.10:8787", trackerConfig.get().serverUrl)
    }

    @Test
    fun `other changes need no restart, and invalid ones are not saved`() = runTest {
        assertFalse(update(ServerConfig(port = 8787, retentionDays = 7)).successValue().restartRequired)

        assertIs<DomainError.Validation>(update(ServerConfig(port = 80)).failureError())
        assertEquals(8787, serverConfig.get().port)
    }
}
