package org.privatetracker.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatchers injected into data sources, so no repository hard-codes [Dispatchers.IO]. */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher

    companion object {
        val Default: DispatcherProvider = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
        }
    }
}
