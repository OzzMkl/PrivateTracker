package org.privatetracker.core.domain.usecase.tracker

import org.privatetracker.core.common.result.FieldViolation
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.testing.T0
import org.privatetracker.core.domain.testing.aFix
import org.privatetracker.core.domain.validation.TrackerConfigValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveTrackingTest {
    /** About 11 m per 0.0001° of latitude. */
    private fun fixAt(secondsAfterT0: Long, northM: Double = 0.0, accuracyM: Float = 8f, speed: Float? = null) =
        aFix(latitude = 19.4326 + northM / 111_000.0, accuracyM = accuracyM, recordedAt = T0.plusSeconds(secondsAfterT0)).copy(speedMps = speed)

    @Test
    fun `a phone whose fixes stay within a few meters for three minutes counts as still`() {
        val detector = StillnessDetector()

        listOf(0L to 0.0, 60L to 6.0, 120L to -4.0).forEach { (t, m) -> detector.onFix(fixAt(t, m)) }
        assertFalse(detector.still.value)
        detector.onFix(fixAt(180, 3.0))

        assertTrue(detector.still.value)
    }

    @Test
    fun `three minutes are not enough with fewer than three fixes`() {
        val detector = StillnessDetector()

        detector.onFix(fixAt(0))
        detector.onFix(fixAt(600))

        assertFalse(detector.still.value)
    }

    @Test
    fun `a fix outside the circle, a reported speed or the motion sensor ends stillness at once`() {
        val detector = StillnessDetector()
        fun settle(start: Long) = (0..3).forEach { detector.onFix(fixAt(start + it * 60L)) }

        settle(0)
        assertTrue(detector.still.value)
        detector.onFix(fixAt(300, northM = 80.0))
        assertFalse(detector.still.value)

        settle(600)
        assertTrue(detector.still.value)
        detector.onFix(fixAt(900, speed = 1.4f))
        assertFalse(detector.still.value)

        settle(1200)
        assertTrue(detector.still.value)
        detector.onMotion()
        assertFalse(detector.still.value)
    }

    @Test
    fun `a poor fix wandering inside its own accuracy does not count as moving`() {
        val detector = StillnessDetector()
        (0..2).forEach { detector.onFix(fixAt(it * 60L)) }

        detector.onFix(fixAt(180, northM = 60.0, accuracyM = 75f))

        assertTrue(detector.still.value)
    }

    @Test
    fun `while still the request slows to the still interval, never faster than the normal one`() {
        val config = TrackerConfig(intervalSeconds = 30, stillIntervalSeconds = 300)

        assertEquals(30_000, config.locationRequest(still = false).intervalMillis)
        assertEquals(300_000, config.locationRequest(still = true).intervalMillis)
        assertEquals(30_000, config.copy(adaptiveInterval = false).locationRequest(still = true).intervalMillis)
        assertEquals(600_000, config.copy(intervalSeconds = 600).locationRequest(still = true).intervalMillis)
    }

    @Test
    fun `with adaptive rate the platform filters no distance, or a phone at rest would send no fixes to notice it`() {
        val config = TrackerConfig(minDistanceM = 50f)

        assertEquals(0f, config.locationRequest(still = false).minDistanceM)
        assertEquals(50f, config.copy(adaptiveInterval = false).locationRequest(still = false).minDistanceM)
    }

    @Test
    fun `the still interval is between one minute and one hour, and adaptive is on by default`() {
        val valid = TrackerConfig(serverUrl = "https://192.168.1.10:8787", deviceName = "Pixel", serverKey = "a2V5")

        assertTrue(valid.adaptiveInterval)
        assertEquals(300, valid.stillIntervalSeconds)
        assertEquals(emptyList(), TrackerConfigValidator.validate(valid))
        assertEquals(
            listOf(FieldViolation("stillIntervalSeconds", FieldViolation.OUT_OF_RANGE)),
            TrackerConfigValidator.validate(valid.copy(stillIntervalSeconds = 30)),
        )
    }
}
