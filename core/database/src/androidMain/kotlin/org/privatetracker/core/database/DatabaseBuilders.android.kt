package org.privatetracker.core.database

import android.content.Context
import androidx.room.Room

// On the phone Room keeps Android's own SQLite, as before 1.0: the files and their WAL stay as they were.

fun ServerDatabase.Companion.build(context: Context): ServerDatabase =
    Room.databaseBuilder(context, ServerDatabase::class.java, ServerDatabase.NAME).build()

fun TrackerDatabase.Companion.build(context: Context): TrackerDatabase =
    Room.databaseBuilder(context, TrackerDatabase::class.java, TrackerDatabase.NAME).build()
