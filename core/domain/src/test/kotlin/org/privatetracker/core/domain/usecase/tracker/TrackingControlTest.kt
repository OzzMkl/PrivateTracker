package org.privatetracker.core.domain.usecase.tracker

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.common.result.DomainError
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.permissions
import org.privatetracker.core.domain.testing.FakePermissionChecker
import org.privatetracker.core.domain.testing.FakeTrackingController
import org.privatetracker.core.domain.testing.InMemoryAppModeRepository
import org.privatetracker.core.domain.testing.InMemoryOutboxRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerConfigRepository
import org.privatetracker.core.domain.testing.RecordingUploadScheduler
import org.privatetracker.core.domain.testing.aLocation
import org.privatetracker.core.domain.testing.failureError
import org.privatetracker.core.domain.testing.successValue
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ControlFixture(
    config: TrackerConfig = TrackerConfig(serverUrl = "http://192.168.1.10:8787", deviceName = "Pixel de Ana"),
    mode: AppMode? = AppMode.TRACKER,
) {
    val config = InMemoryTrackerConfigRepository(config)
    val permissions = FakePermissionChecker()
    val controller = FakeTrackingController()
    val scheduler = RecordingUploadScheduler()
    val modes = InMemoryAppModeRepository(mode)
    val outbox = InMemoryOutboxRepository()
    val start = StartTracking(this.config, permissions, controller)
    val stop = StopTracking(this.config, controller, scheduler)
    val restore = RestoreTracking(modes, this.config, permissions, controller)
    val observe = ObserveTrackingStatus(this.config, controller, outbox)
}

class StartTrackingTest {
    @Test
    fun `a valid configuration with permissions turns tracking on and starts the service`() = runTest {
        val fixture = ControlFixture()

        fixture.start().successValue()

        assertTrue(fixture.config.get().trackingEnabled)
        assertEquals(1, fixture.controller.starts)
    }

    @Test
    fun `without a server url tracking does not start`() = runTest {
        val fixture = ControlFixture(TrackerConfig(deviceName = "Pixel de Ana"))

        assertEquals(DomainError.NotConfigured, fixture.start().failureError())
        assertEquals(0, fixture.controller.starts)
        assertFalse(fixture.config.get().trackingEnabled)
    }

    @Test
    fun `missing location or notification permission blocks the start`() = runTest {
        val fixture = ControlFixture()
        fixture.permissions.granted -= setOf(AppPermission.PRECISE_LOCATION, AppPermission.NOTIFICATIONS)

        val error = assertIs<DomainError.Permission>(fixture.start().failureError())

        assertEquals(setOf(AppPermission.PRECISE_LOCATION, AppPermission.NOTIFICATIONS), error.permissions())
        assertEquals(0, fixture.controller.starts)
    }

    @Test
    fun `local network access is required only for a server on the local network`() = runTest {
        fun withoutLocalNetwork(serverUrl: String) =
            ControlFixture(TrackerConfig(serverUrl = serverUrl, deviceName = "Pixel"))
                .also { it.permissions.granted -= AppPermission.LOCAL_NETWORK }

        val lan = withoutLocalNetwork("http://192.168.1.10:8787").start().failureError()
        assertEquals(setOf(AppPermission.LOCAL_NETWORK), assertIs<DomainError.Permission>(lan).permissions())

        // Same phone, and a VPN address such as Tailscale's: the system does not ask for local network access.
        withoutLocalNetwork("http://127.0.0.1:8787").start().successValue()
        withoutLocalNetwork("http://100.101.102.103:8787").start().successValue()
    }

    @Test
    fun `a permission this android version lacks needs no grant`() = runTest {
        val fixture = ControlFixture()
        fixture.permissions.granted -= AppPermission.LOCAL_NETWORK
        fixture.permissions.applicable -= AppPermission.LOCAL_NETWORK

        fixture.start().successValue()
    }
}

class StopTrackingTest {
    @Test
    fun `stopping turns tracking off, stops the service and schedules the remaining upload`() = runTest {
        val fixture = ControlFixture()
        fixture.start().successValue()

        fixture.stop()

        assertFalse(fixture.config.get().trackingEnabled)
        assertEquals(1, fixture.controller.stops)
        assertEquals(listOf(Duration.ZERO), fixture.scheduler.scheduled)
    }
}

class RestoreTrackingTest {
    private fun enabled(startOnBoot: Boolean = true) = TrackerConfig(
        serverUrl = "http://192.168.1.10:8787",
        deviceName = "Pixel de Ana",
        startOnBoot = startOnBoot,
        trackingEnabled = true,
    )

    @Test
    fun `at boot tracking restarts when enabled, wanted on boot and allowed in the background`() = runTest {
        val fixture = ControlFixture(enabled())

        assertEquals(RestoreResult.STARTED, fixture.restore(RestoreTrigger.BOOT))
        assertEquals(1, fixture.controller.starts)
    }

    @Test
    fun `at boot nothing starts without start on boot or without background location`() = runTest {
        assertEquals(RestoreResult.NOT_WANTED, ControlFixture(enabled(startOnBoot = false)).restore(RestoreTrigger.BOOT))

        val noBackground = ControlFixture(enabled())
        noBackground.permissions.granted -= AppPermission.BACKGROUND_LOCATION
        assertEquals(RestoreResult.MISSING_PERMISSIONS, noBackground.restore(RestoreTrigger.BOOT))
        assertEquals(0, noBackground.controller.starts)
    }

    @Test
    fun `opening the app restarts tracking killed with the process, even without background location`() = runTest {
        val fixture = ControlFixture(enabled(startOnBoot = false))
        fixture.permissions.granted -= AppPermission.BACKGROUND_LOCATION

        assertEquals(RestoreResult.STARTED, fixture.restore(RestoreTrigger.APP_OPENED))
    }

    @Test
    fun `nothing restarts when tracking is off, already running or the mode does not track`() = runTest {
        assertEquals(RestoreResult.NOT_WANTED, ControlFixture().restore(RestoreTrigger.APP_OPENED))
        assertEquals(RestoreResult.NOT_WANTED, ControlFixture(enabled(), AppMode.SERVER).restore(RestoreTrigger.APP_OPENED))
        assertEquals(RestoreResult.NOT_WANTED, ControlFixture(enabled(), mode = null).restore(RestoreTrigger.APP_OPENED))

        val running = ControlFixture(enabled())
        running.controller.start()
        assertEquals(RestoreResult.ALREADY_RUNNING, running.restore(RestoreTrigger.APP_OPENED))
        assertEquals(1, running.controller.starts)
    }

    @Test
    fun `an invalid stored configuration is not restored`() = runTest {
        val fixture = ControlFixture(enabled().copy(serverUrl = "not a url"))

        assertEquals(RestoreResult.NOT_CONFIGURED, fixture.restore(RestoreTrigger.APP_OPENED))
    }
}

class ObserveTrackingStatusTest {
    @Test
    fun `status combines configuration, service activity and queue size`() = runTest {
        val fixture = ControlFixture(TrackerConfig(serverUrl = "http://192.168.1.10:8787", maxQueueSize = 10))
        repeat(8) { fixture.outbox.enqueue(aLocation(it + 1), maxSize = 10) }
        fixture.controller.start()

        val status = fixture.observe().first()

        assertTrue(status.running)
        assertEquals(8, status.queueSize)
        assertTrue(status.queueNearlyFull)
    }
}
