package org.privatetracker.core.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** One small JSON file per store. A field added later only needs a default value. */
class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        StoreJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(StoreJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

/**
 * Creates the store `files/datastore/<name>.json`. A corrupt file is replaced by the defaults instead
 * of crashing the app. The directory is excluded from cloud backup (see backup rules).
 */
fun <T> createJsonDataStore(context: Context, name: String, serializer: KSerializer<T>, defaultValue: T): DataStore<T> =
    DataStoreFactory.create(
        serializer = JsonSerializer(serializer, defaultValue),
        corruptionHandler = ReplaceFileCorruptionHandler { defaultValue },
        produceFile = { context.dataStoreFile("$name.json") },
    )
