package org.privatetracker.feature.geofences.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.domain.port.GeofenceAlerts
import org.privatetracker.feature.geofences.notification.AndroidGeofenceAlerts

@Module
@InstallIn(SingletonComponent::class)
abstract class GeofencesModule {
    @Binds abstract fun alerts(alerts: AndroidGeofenceAlerts): GeofenceAlerts
}
