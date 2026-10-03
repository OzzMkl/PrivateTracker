package org.privatetracker.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.privatetracker.core.domain.model.LocationFix
import org.privatetracker.core.domain.model.LocationPriority
import org.privatetracker.core.domain.port.LocationRequestSpec
import org.privatetracker.core.domain.port.LocationSource
import java.time.Instant
import javax.inject.Inject
import android.location.Location as AndroidLocation

/**
 * Location from the AOSP LocationManager, without Google Play Services. Prefers the platform
 * fused provider (API 31+, when present), then GPS, then network.
 */
class PlatformLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // Callers check location permission; a SecurityException ends the flow.
    override fun locations(request: LocationRequestSpec): Flow<LocationFix> = callbackFlow {
        val listener = LocationListenerCompat { location -> trySend(location.toFix()) }
        val compatRequest = LocationRequestCompat.Builder(request.intervalMillis)
            .setQuality(request.priority.toQuality())
            .setMinUpdateIntervalMillis(request.intervalMillis / 2)
            .setMinUpdateDistanceMeters(request.minDistanceM)
            .build()
        LocationManagerCompat.requestLocationUpdates(
            manager,
            bestProvider(),
            compatRequest,
            ContextCompat.getMainExecutor(context),
            listener,
        )
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    private fun bestProvider(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER) ->
            LocationManager.FUSED_PROVIDER
        manager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> LocationManager.NETWORK_PROVIDER
    }

    private fun LocationPriority.toQuality(): Int = when (this) {
        LocationPriority.HIGH_ACCURACY -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
        LocationPriority.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
        LocationPriority.LOW_POWER -> LocationRequestCompat.QUALITY_LOW_POWER
    }
}

internal fun AndroidLocation.toFix(): LocationFix = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracyM = if (hasAccuracy()) accuracy else null,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    provider = provider,
    isMock = LocationCompat.isMock(this),
    recordedAt = Instant.ofEpochMilli(time),
)
