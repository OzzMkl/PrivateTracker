package org.privatetracker.core.domain.repository

import kotlinx.coroutines.flow.Flow
import org.privatetracker.core.domain.model.AppMode

/** The mode the user chose; null until onboarding ends. */
interface AppModeRepository {
    fun observe(): Flow<AppMode?>
    suspend fun get(): AppMode?
    suspend fun set(mode: AppMode)
}
