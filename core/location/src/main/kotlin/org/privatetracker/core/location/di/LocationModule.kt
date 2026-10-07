package org.privatetracker.core.location.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.domain.port.LocationSource
import org.privatetracker.core.domain.port.MotionSensor
import org.privatetracker.core.domain.port.PermissionChecker
import org.privatetracker.core.location.AndroidPermissionChecker
import org.privatetracker.core.location.PlatformLocationSource
import org.privatetracker.core.location.SignificantMotionSensor

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds abstract fun locationSource(source: PlatformLocationSource): LocationSource

    @Binds abstract fun permissionChecker(checker: AndroidPermissionChecker): PermissionChecker

    @Binds abstract fun motionSensor(sensor: SignificantMotionSensor): MotionSensor
}
