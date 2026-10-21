package org.privatetracker.server.linux

import org.privatetracker.server.hostkeys.lockFile
import org.privatetracker.server.hostkeys.prepareOwnerOnlyDirectory
import java.io.IOException
import java.nio.file.Path

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
    fun lock(): AutoCloseable? = lockFile(root.resolve("server.lock"))

    /**
     * Creates the directory, or closes one that others could enter. Throws IOException, with a message
     * for the owner, when it cannot or when the path is too long for the admin socket.
     */
    fun prepare() {
        // The socket stays here rather than in /tmp, where another user could take its name first.
        if (adminSocket.toString().encodeToByteArray().size > MAX_SOCKET_PATH_BYTES) {
            throw IOException("la ruta es demasiado larga para el socket de administración (Linux admite $MAX_SOCKET_PATH_BYTES bytes); usa una más corta con --data")
        }
        prepareOwnerOnlyDirectory(root)
    }

    companion object {
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
