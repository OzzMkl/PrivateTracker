package org.privatetracker.core.data.repository

import androidx.room.RoomDatabase

/**
 * Runs [block] in one SQLite transaction that the DAO calls inside it join. Android keeps Room's
 * withTransaction, as before 1.0; the JVM, which has no such API, uses a writer connection.
 */
internal expect suspend fun <T> RoomDatabase.inTransaction(block: suspend () -> T): T
