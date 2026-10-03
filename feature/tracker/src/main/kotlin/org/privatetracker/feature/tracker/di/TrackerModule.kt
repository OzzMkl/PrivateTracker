package org.privatetracker.feature.tracker.di

import android.content.Context
import android.os.BatteryManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.privatetracker.core.domain.port.BatteryLevelProvider
import org.privatetracker.core.domain.port.TrackingController
import org.privatetracker.core.domain.port.UploadScheduler
import org.privatetracker.feature.tracker.service.ServiceTrackingController
import org.privatetracker.feature.tracker.worker.WorkManagerUploadScheduler
import javax.inject.Inject

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerModule {
    @Binds abstract fun trackingController(controller: ServiceTrackingController): TrackingController

    @Binds abstract fun uploadScheduler(scheduler: WorkManagerUploadScheduler): UploadScheduler

    @Binds abstract fun battery(provider: AndroidBatteryLevelProvider): BatteryLevelProvider
}

class AndroidBatteryLevelProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : BatteryLevelProvider {
    override fun currentLevelPct(): Int? =
        context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
}
