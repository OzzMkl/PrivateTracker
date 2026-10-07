package org.privatetracker.core.domain.usecase.tracker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.privatetracker.core.domain.geo.distanceMeters
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.TrackerConfig
import org.privatetracker.core.domain.port.LocationRequestSpec
import java.time.Duration

/**
 * Tells from the fixes alone whether the phone lies still, from 0.6 on, so the tracker can ask for
 * fixes less often. Still means: for at least [stillAfter] and [minFixes] fixes, none left a circle
 * of [radiusM] (or of the fix's own accuracy, when worse) around the first, and none reported moving.
 * Any fix outside, a reported speed, or the motion sensor ([onMotion]) ends it at once.
 *
 * One instance per tracking session; not thread-safe, fixes arrive one at a time.
 */
class StillnessDetector(
    private val radiusM: Double = STILL_RADIUS_M,
    private val stillAfter: Duration = STILL_AFTER,
    private val minFixes: Int = MIN_FIXES,
) {
    private val _still = MutableStateFlow(false)
    val still: StateFlow<Boolean> = _still.asStateFlow()

    private var anchor: LocationFix? = null
    private var fixesNearAnchor = 0

    fun onFix(fix: LocationFix) {
        val first = anchor
        val limit = maxOf(radiusM, fix.accuracyM?.toDouble() ?: 0.0)
        val moved = first == null ||
            (fix.speedMps ?: 0f) > MOVING_SPEED_MPS ||
            distanceMeters(first.latitude, first.longitude, fix.latitude, fix.longitude) > limit
        if (moved) {
            restartAt(fix)
            return
        }
        fixesNearAnchor++
        if (fixesNearAnchor >= minFixes && Duration.between(checkNotNull(first).recordedAt, fix.recordedAt) >= stillAfter) {
            _still.value = true
        }
    }

    /** The motion sensor felt the phone move: fixes go back to the normal rate until it settles again. */
    fun onMotion() {
        anchor = null
        fixesNearAnchor = 0
        _still.value = false
    }

    private fun restartAt(fix: LocationFix) {
        anchor = fix
        fixesNearAnchor = 1
        _still.value = false
    }

    companion object {
        /** Indoor fixes wander some tens of meters without the phone moving. */
        const val STILL_RADIUS_M = 30.0
        val STILL_AFTER: Duration = Duration.ofMinutes(3)
        const val MIN_FIXES = 3

        /** Walking is above 1 m/s; GPS reports a little speed even at rest. */
        const val MOVING_SPEED_MPS = 0.7f
    }
}

/**
 * What to ask the location source for, given the settings and whether the phone lies still. With
 * adaptive rate the platform filters no distance: the detector needs the fixes of a phone at rest to
 * notice it rests, and RecordLocation still keeps only those [TrackerConfig.minDistanceM] apart.
 */
fun TrackerConfig.locationRequest(still: Boolean): LocationRequestSpec =
    LocationRequestSpec(effectiveIntervalSeconds(still) * 1_000L, if (adaptiveInterval) 0f else minDistanceM, priority)
