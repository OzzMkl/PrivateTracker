package org.privatetracker.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * server.db at [file], on the SQLite that ships with the driver: the same engine on every host, so a
 * file copied from a phone opens here. Room turns WAL on, as on Android.
 */
fun ServerDatabase.Companion.build(file: File): ServerDatabase =
    Room.databaseBuilder<ServerDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/** An empty server database in memory, for tests. */
fun ServerDatabase.Companion.inMemory(): ServerDatabase =
    Room.inMemoryDatabaseBuilder<ServerDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
