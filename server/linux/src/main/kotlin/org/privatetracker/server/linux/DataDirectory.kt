package org.privatetracker.server.linux

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Everything the server keeps lives in one directory that only its user can enter (0700): in Docker,
 * the volume. Its keys are protected by these file modes alone, as nginx's TLS key is.
 */
class DataDirectory(val root: Path) {
    val database: Path get() = root.resolve("server.db")

    /** The identity key, which is also the TLS key; trackers pin it. Losing it means pairing every tracker again. */
    val identity: Path get() = root.resolve("identity.p12")

    /** One AES key per encryption key; deleting an entry destroys that encryption key for good. */
    val encryptionVault: Path get() = root.resolve("encryption-vault.p12")

    val encryptionKeys: Path get() = root.resolve("encryption-keys.json")

    /** The web app's accounts: names and password hashes (1.0). */
    val webAccounts: Path get() = root.resolve("web-accounts.json")

    /** The web app's signed-in browsers, by the hash of their token (1.0). */
    val webSessions: Path get() = root.resolve("web-sessions.json")

    /** Signs the web app's known-browser cookies (1.0). */
    val webBrowserKey: Path get() = root.resolve("web-browser.key")

    /** Where the command line talks to the running server; only this directory's owner can reach it. */
    val adminSocket: Path get() = root.resolve("admin.sock")

    /**
     * Takes the directory for this process: an OS file lock, released when it closes or the process
     * dies. Null when another server holds it, even one that started at the same moment.
     */
    fun lock(): AutoCloseable? {
        val channel = FileChannel.open(
            root.resolve("server.lock"),
            setOf(StandardOpenOption.CREATE, WRITE),
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE),
        )
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return AutoCloseable { channel.close() }
    }

    /**
     * Creates the directory, or closes one that others could enter. Throws IOException, with a message
     * for the owner, when it cannot or when the path is too long for the admin socket.
     */
    fun prepare() {
        // The socket stays here rather than in /tmp, where another user could take its name first.
        if (adminSocket.toString().encodeToByteArray().size > MAX_SOCKET_PATH_BYTES) {
            throw IOException("la ruta es demasiado larga para el socket de administración (Linux admite $MAX_SOCKET_PATH_BYTES bytes); usa una más corta con --data")
        }
        if (!Files.isDirectory(root)) {
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY))
        }
        if (Files.getPosixFilePermissions(root) != OWNER_ONLY_DIRECTORY) {
            try {
                Files.setPosixFilePermissions(root, OWNER_ONLY_DIRECTORY)
            } catch (e: FileSystemException) {
                throw IOException("otros usuarios pueden entrar y este usuario no puede impedirlo (chmod 700)", e)
            }
        }
    }

    companion object {
        val OWNER_ONLY_DIRECTORY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
        val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

        /** sun_path holds 108 bytes on Linux, the last one for the terminating zero. */
        const val MAX_SOCKET_PATH_BYTES = 107

        /** The directory from `--data`, else PRIVATETRACKER_DATA, else ~/.local/share/privatetracker. */
        fun locate(option: String?, environment: Map<String, String>): DataDirectory {
            val path = option ?: environment["PRIVATETRACKER_DATA"]?.takeIf { it.isNotBlank() }
                ?: Path.of(environment["XDG_DATA_HOME"] ?: "${System.getProperty("user.home")}/.local/share", "privatetracker").toString()
            return DataDirectory(Path.of(path).toAbsolutePath().normalize())
        }
    }
}

/**
 * Replaces [target] with [bytes] so that a crash leaves the old file or the new one, never half of
 * either, and the new file is readable by its owner only (0600) from the moment it exists.
 */
fun writeAtomically(target: Path, bytes: ByteArray) {
    val directory = target.toAbsolutePath().parent
    val temp = Files.createTempFile(directory, ".${target.fileName}", ".tmp", PosixFilePermissions.asFileAttribute(DataDirectory.OWNER_ONLY_FILE))
    try {
        FileChannel.open(temp, WRITE).use { channel ->
            channel.write(ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        // The rename itself survives a power cut only once the directory is on disk too.
        FileChannel.open(directory, READ).use { it.force(true) }
    } finally {
        Files.deleteIfExists(temp)
    }
}
