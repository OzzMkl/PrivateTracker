package org.privatetracker.feature.server.receiver

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
import org.privatetracker.core.domain.usecase.server.RestoreServer

/** Starts the server after a reboot when the user turned on autostart. */
class ServerBootReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun restoreServer(): RestoreServer
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val restore = EntryPointAccessors.fromApplication(context, Dependencies::class.java).restoreServer()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.i(TAG, "Server started after boot: ${restore()}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ServerBootReceiver"
    }
}
