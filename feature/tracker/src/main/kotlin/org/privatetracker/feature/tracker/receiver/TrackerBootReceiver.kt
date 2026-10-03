package org.privatetracker.feature.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.privatetracker.core.domain.usecase.tracker.RestoreTracking
import org.privatetracker.core.domain.usecase.tracker.RestoreTrigger

/**
 * Restarts tracking after a reboot, if the user asked for it. Android 15 still lets BOOT_COMPLETED
 * start a location service, but only with location "all the time"; RestoreTracking checks both.
 */
class TrackerBootReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun restoreTracking(): RestoreTracking
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val restore = EntryPointAccessors.fromApplication(context, Dependencies::class.java).restoreTracking()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.i(TAG, "Restore after boot: ${restore(RestoreTrigger.BOOT)}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TrackerBootReceiver"
    }
}
