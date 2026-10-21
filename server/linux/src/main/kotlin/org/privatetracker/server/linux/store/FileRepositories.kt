package org.privatetracker.server.linux.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.privatetracker.core.domain.model.EncryptionKey
import org.privatetracker.core.domain.model.ServerConfig
import org.privatetracker.core.domain.model.StoredEncryptionKey
import org.privatetracker.core.domain.repository.EncryptionKeyRepository
import org.privatetracker.core.domain.repository.ServerConfigRepository
import org.privatetracker.server.hostkeys.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The server's encryption keys, public halves and protected private halves, in a JSON file (0600).
 * Each change replaces the file atomically before anyone sees it, as DataStore does on a phone.
 */
class JsonFileEncryptionKeyRepository(private val path: Path) : EncryptionKeyRepository {
    private val mutex = Mutex()
    private val keys = MutableStateFlow(read())

    override fun observe(): Flow<List<StoredEncryptionKey>> = keys.asStateFlow()

    override suspend fun get(): List<StoredEncryptionKey> = keys.value

    override suspend fun update(transform: (List<StoredEncryptionKey>) -> List<StoredEncryptionKey>): List<StoredEncryptionKey> =
        mutex.withLock {
            val next = transform(keys.value)
            if (next != keys.value) {
                writeAtomically(path, json.encodeToString(KeysFile.serializer(), KeysFile(next.map { it.toData() })).encodeToByteArray())
                keys.value = next
            }
            next
        }

    private fun read(): List<StoredEncryptionKey> =
        if (Files.exists(path)) json.decodeFromString(KeysFile.serializer(), Files.readString(path)).keys.map { it.toDomain() } else emptyList()

    @Serializable
    private data class KeysFile(val keys: List<KeyData> = emptyList())

    @Serializable
    private data class KeyData(
        val id: String,
        val publicKey: String,
        val useUntilMillis: Long,
        val createdAtMillis: Long,
        val protectedPrivateKey: String,
    )

    private fun StoredEncryptionKey.toData() =
        KeyData(key.id, key.publicKey, key.useUntil.toEpochMilli(), createdAt.toEpochMilli(), protectedPrivateKey)

    private fun KeyData.toDomain() = StoredEncryptionKey(
        key = EncryptionKey(id, publicKey, Instant.ofEpochMilli(useUntilMillis)),
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        protectedPrivateKey = protectedPrivateKey,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

/**
 * The Linux server's settings come from its flags and environment at start, so they live in memory:
 * a change made while it runs lasts until it stops, and the next start reads them again.
 */
class SettingsServerConfigRepository(initial: ServerConfig) : ServerConfigRepository {
    private val config = MutableStateFlow(initial)

    val current: StateFlow<ServerConfig> = config.asStateFlow()

    override fun observe(): Flow<ServerConfig> = config.asStateFlow()

    override suspend fun get(): ServerConfig = config.value

    override suspend fun update(transform: (ServerConfig) -> ServerConfig): ServerConfig = config.updateAndGet(transform)
}
