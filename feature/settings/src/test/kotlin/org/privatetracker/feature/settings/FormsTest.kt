package org.privatetracker.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.model.keyFingerprint

class FormsTest {
    @Test
    fun trackerFormRoundTripsTheConfigAndKeepsWhatItDoesNotEdit() {
        val config = TrackerConfig(serverUrl = "https://192.168.1.10:8787", minDistanceM = 12.5f, trackingEnabled = true)

        val form = TrackerForm.from(config)
        val (back, violations) = form.copy(intervalSeconds = "30", priority = LocationPriority.LOW_POWER).toConfig(config)

        assertEquals("12.5", form.minDistanceM)
        assertEquals("100", form.maxAccuracyM)
        assertTrue(violations.isEmpty())
        assertEquals(config.copy(intervalSeconds = 30, priority = LocationPriority.LOW_POWER), back)
    }

    @Test
    fun theFingerprintFieldShowsThePinnedServerHoweverItWasPinned() {
        val typed = TrackerConfig(serverUrl = "https://192.168.1.10:8787", serverFingerprint = "3F9A-01BC-77D2-E410")
        val paired = TrackerConfig(serverUrl = "https://192.168.1.10:8787", serverKey = "a2V5")

        assertEquals("3F9A-01BC-77D2-E410", TrackerForm.from(typed).serverFingerprint)
        assertEquals(keyFingerprint("a2V5"), TrackerForm.from(paired).serverFingerprint)
        assertEquals("", TrackerForm.from(TrackerConfig()).serverFingerprint)
    }

    @Test
    fun aDecimalCommaIsAccepted() {
        val (config, violations) = TrackerForm.from(TrackerConfig()).copy(maxAccuracyM = "7,5").toConfig(TrackerConfig())

        assertTrue(violations.isEmpty())
        assertEquals(7.5f, config.maxAccuracyM)
    }

    @Test
    fun textThatIsNotANumberBecomesAFieldViolation() {
        val (_, tracker) = TrackerForm.from(TrackerConfig()).copy(intervalSeconds = "diez", batchSize = "").toConfig(TrackerConfig())
        val (_, server) = ServerForm.from(ServerConfig()).copy(port = "80a").toConfig(ServerConfig())

        assertEquals(
            listOf(FieldViolation("intervalSeconds", FieldViolation.INVALID_FORMAT), FieldViolation("batchSize", FieldViolation.INVALID_FORMAT)),
            tracker,
        )
        assertEquals(listOf(FieldViolation("port", FieldViolation.INVALID_FORMAT)), server)
    }
}
