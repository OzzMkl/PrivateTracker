package org.privatetracker.core.domain.usecase.common

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.privatetracker.core.domain.model.AppMode
import org.privatetracker.core.domain.model.AppPermission
import org.privatetracker.core.domain.model.PermissionStatus
import org.privatetracker.core.domain.model.Requirement
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.testing.FakePermissionChecker
import org.privatetracker.core.domain.testing.FakeServerController
import org.privatetracker.core.domain.testing.FakeServerKeys
import org.privatetracker.core.domain.testing.FakeTrackingController
import org.privatetracker.core.domain.testing.InMemoryAppModeRepository
import org.privatetracker.core.domain.testing.InMemoryServerConfigRepository
import org.privatetracker.core.domain.testing.InMemoryTrackerConfigRepository
import org.privatetracker.core.domain.testing.RecordingUploadScheduler
import org.privatetracker.core.domain.usecase.tracker.StopTracking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetAppModeTest {
    private val modes = InMemoryAppModeRepository()
    private val trackerConfig = InMemoryTrackerConfigRepository(TrackerConfig(trackingEnabled = true))
    private val serverConfig = InMemoryServerConfigRepository(ServerConfig(port = 9000))
    private val tracking = FakeTrackingController()
    private val server = FakeServerController()
    private val setMode = SetAppMode(
        modes,
        trackerConfig,
        serverConfig,
        TrustOwnServer(trackerConfig, serverConfig, FakeServerKeys()),
        StopTracking(trackerConfig, tracking, RecordingUploadScheduler()),
        server,
    )

    @Test
    fun `the mode is saved and observed`() = runTest {
        assertNull(ObserveAppMode(modes)().first())

        setMode(AppMode.TRACKER)

        assertEquals(AppMode.TRACKER, ObserveAppMode(modes)().first())
    }

    @Test
    fun `turning a role off stops its service`() = runTest {
        setMode(AppMode.SERVER)
        assertEquals(1, tracking.stops)
        assertFalse(trackerConfig.get().trackingEnabled)
        assertEquals(0, server.stops)

        setMode(AppMode.TRACKER)
        assertEquals(1, server.stops)
    }

    @Test
    fun `both roles on one phone point the tracker at its own server, trusted by its key, unless it has a url`() = runTest {
        setMode(AppMode.TRACKER_AND_SERVER)
        assertEquals("https://127.0.0.1:9000", trackerConfig.get().serverUrl)
        assertEquals(FakeServerKeys.SERVER_KEY, trackerConfig.get().serverKey)

        trackerConfig.update { it.copy(serverUrl = "https://192.168.1.10:8787") }
        setMode(AppMode.TRACKER_AND_SERVER)
        assertEquals("https://192.168.1.10:8787", trackerConfig.get().serverUrl)
        assertEquals(0, tracking.stops)
    }
}

class TrustOwnServerTest {
    private val serverConfig = InMemoryServerConfigRepository(ServerConfig(port = 9000))

    private suspend fun trustedKeyFor(config: TrackerConfig): String {
        val trackerConfig = InMemoryTrackerConfigRepository(config)
        TrustOwnServer(trackerConfig, serverConfig, FakeServerKeys())()
        return trackerConfig.get().serverKey
    }

    @Test
    fun `a tracker on the server's own phone with nothing pinned trusts that server's key, as after updating from 0_3`() = runTest {
        assertEquals(FakeServerKeys.SERVER_KEY, trustedKeyFor(TrackerConfig(serverUrl = "https://127.0.0.1:9000")))
    }

    @Test
    fun `another address or a pin already set is left alone`() = runTest {
        assertEquals("", trustedKeyFor(TrackerConfig(serverUrl = "https://192.168.1.10:9000")))
        assertEquals("", trustedKeyFor(TrackerConfig(serverUrl = "https://127.0.0.1:9000", serverFingerprint = "3F9A-01BC-77D2-E410")))
        assertEquals("b2xk", trustedKeyFor(TrackerConfig(serverUrl = "https://127.0.0.1:9000", serverKey = "b2xk")))
    }
}

class CheckPermissionsTest {
    private val checker = FakePermissionChecker()
    private val check = CheckPermissions(checker)

    private fun requirementsOf(mode: AppMode) = check(mode).items.associate { it.permission to it.requirement }

    @Test
    fun `each mode requires what its service cannot run without`() {
        assertEquals(
            mapOf(
                AppPermission.PRECISE_LOCATION to Requirement.REQUIRED,
                AppPermission.BACKGROUND_LOCATION to Requirement.RECOMMENDED,
                AppPermission.NOTIFICATIONS to Requirement.REQUIRED,
                AppPermission.LOCAL_NETWORK to Requirement.RECOMMENDED,
                AppPermission.BATTERY_OPTIMIZATION_EXEMPTION to Requirement.RECOMMENDED,
            ),
            requirementsOf(AppMode.TRACKER),
        )
        assertEquals(
            mapOf(
                AppPermission.NOTIFICATIONS to Requirement.RECOMMENDED,
                AppPermission.LOCAL_NETWORK to Requirement.REQUIRED,
                AppPermission.BATTERY_OPTIMIZATION_EXEMPTION to Requirement.RECOMMENDED,
            ),
            requirementsOf(AppMode.SERVER),
        )
        // With both roles, the stricter requirement wins.
        assertEquals(Requirement.REQUIRED, requirementsOf(AppMode.TRACKER_AND_SERVER)[AppPermission.LOCAL_NETWORK])
        assertEquals(Requirement.REQUIRED, requirementsOf(AppMode.TRACKER_AND_SERVER)[AppPermission.NOTIFICATIONS])
    }

    @Test
    fun `the report lists what is missing and leaves out what this android version lacks`() {
        checker.applicable -= AppPermission.LOCAL_NETWORK
        checker.granted -= setOf(AppPermission.NOTIFICATIONS, AppPermission.BACKGROUND_LOCATION)

        val report = check(AppMode.TRACKER)

        assertFalse(report.items.any { it.permission == AppPermission.LOCAL_NETWORK })
        assertEquals(setOf(AppPermission.NOTIFICATIONS), report.missingRequired)
        assertFalse(report.allRequiredGranted)
        assertTrue(PermissionStatus(AppPermission.BACKGROUND_LOCATION, Requirement.RECOMMENDED, false) in report.items)
    }
}
