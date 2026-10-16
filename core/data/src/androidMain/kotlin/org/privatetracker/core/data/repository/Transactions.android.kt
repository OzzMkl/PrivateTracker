package org.privatetracker.core.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction

internal actual suspend fun <T> RoomDatabase.inTransaction(block: suspend () -> T): T = withTransaction { block() }
