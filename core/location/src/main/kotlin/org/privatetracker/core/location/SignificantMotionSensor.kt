package org.privatetracker.core.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.privatetracker.core.domain.port.MotionSensor
import javax.inject.Inject

/**
 * Android's significant-motion sensor (API 18+): a low-power trigger that fires once when the phone
 * starts moving, even while it sleeps, and then disables itself; it is armed again after each event.
 * Most phones have it, emulators usually not: without it the flow ends at once and the tracker
 * notices movement from its next fix instead.
 */
class SignificantMotionSensor @Inject constructor(
    @ApplicationContext private val context: Context,
) : MotionSensor {
    override fun motions(): Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                trySend(Unit)
                manager.requestTriggerSensor(this, sensor)
            }
        }
        manager.requestTriggerSensor(listener, sensor)
        awaitClose { manager.cancelTriggerSensor(listener, sensor) }
    }
}
