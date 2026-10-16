package org.privatetracker.core.data.repository

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/** IMMEDIATE takes the write lock at once, as Android's withTransaction does; nested calls become savepoints. */
internal actual suspend fun <T> RoomDatabase.inTransaction(block: suspend () -> T): T =
    useWriterConnection { connection -> connection.immediateTransaction { block() } }
