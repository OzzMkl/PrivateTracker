package org.privatetracker.tools.simulator

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Copies server.db out of the phone with `adb exec-out run-as`, which only works on debuggable builds.
 * Room keeps recent writes in server.db-wal until a checkpoint, so that file comes along; SQLite
 * replays it when the copy is opened. The -shm index is left behind: SQLite rebuilds it.
 *
 * The two files are read one after the other, so a write or checkpoint in between would pair a
 * database with a WAL from another moment. Every file is therefore copied twice, and the copy only
 * counts when both rounds match byte for byte.
 */
class AdbDatabasePuller(
    private val adb: String,
    private val serial: String?,
    private val packageName: String,
) {
    fun pull(targetDir: Path): Path {
        val files = listDatabases()
        if (DB !in files) {
            throw UsageException(
                "No se encontró databases/$DB en $packageName. ¿Es un build debug y el modo Servidor ya se usó? " +
                    "adb respondió: ${files.joinToString(" ")}",
            )
        }
        val names = listOf(DB, DB_WAL).filter { it in files }
        Files.createDirectories(targetDir)
        val check = Files.createTempDirectory("server-db")
        try {
            val stable = (1..ATTEMPTS).any {
                names.forEach { copy(it, targetDir) }
                names.forEach { copy(it, check) }
                names.all { Files.mismatch(targetDir.resolve(it), check.resolve(it)) == -1L }
            }
            if (!stable) throw UsageException("server.db cambió mientras se copiaba; detén el servidor en la app y vuelve a intentar")
        } finally {
            check.toFile().deleteRecursively()
        }

        val db = targetDir.resolve(DB)
        if (!startsWith(db, SQLITE_MAGIC)) throw UsageException("La copia de $DB no es una base SQLite: $db")
        Files.deleteIfExists(targetDir.resolve("$DB-shm"))
        val wal = targetDir.resolve(DB_WAL)
        if (DB_WAL !in names) Files.deleteIfExists(wal)
        // An empty or unreadable WAL holds nothing; dropping it is safer than handing SQLite junk.
        if (Files.exists(wal) && !WAL_MAGICS.any { startsWith(wal, it) }) Files.delete(wal)
        return db
    }

    private fun listDatabases(): Set<String> {
        val output = Files.createTempFile("adb", ".out").toFile()
        try {
            execute(listOf("shell", "run-as", packageName, "ls", "databases"), output)
            return output.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } finally {
            output.delete()
        }
    }

    private fun copy(name: String, targetDir: Path) {
        execute(listOf("exec-out", "run-as", packageName, "cat", "databases/$name"), targetDir.resolve(name).toFile())
    }

    /** Output goes to files, not pipes: reading a pipe would block until adb exits and defeat the timeout. */
    private fun execute(args: List<String>, output: File) {
        val errors = Files.createTempFile("adb", ".err").toFile()
        try {
            val process = try {
                ProcessBuilder(listOf(adb) + (serial?.let { listOf("-s", it) } ?: emptyList()) + args)
                    .redirectOutput(output)
                    .redirectError(errors)
                    .start()
            } catch (e: IOException) {
                throw UsageException("No se pudo ejecutar $adb (${e.message}); indica la ruta con --adb")
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw UsageException("adb no respondió en $TIMEOUT_SECONDS s")
            }
            if (process.exitValue() != 0) {
                val message = (errors.readText() + output.takeIf { it.length() < 4_096 }?.readText().orEmpty()).trim()
                throw UsageException("adb terminó con error ${process.exitValue()}: $message")
            }
        } finally {
            errors.delete()
        }
    }

    private fun startsWith(file: Path, magic: ByteArray): Boolean =
        Files.newInputStream(file).use { it.readNBytes(magic.size) }.contentEquals(magic)

    companion object {
        const val DEFAULT_PACKAGE = "org.privatetracker"
        private const val DB = "server.db"
        private const val DB_WAL = "$DB-wal"
        private const val ATTEMPTS = 3
        private const val TIMEOUT_SECONDS = 120L
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val WAL_MAGICS = listOf(
            byteArrayOf(0x37, 0x7f, 0x06, 0x82.toByte()),
            byteArrayOf(0x37, 0x7f, 0x06, 0x83.toByte()),
        )

        /** `--adb`, else the SDK under ANDROID_HOME or Android Studio's default folder, else the PATH. */
        fun findAdb(explicit: String?): String {
            if (explicit != null) return explicit
            val home = System.getProperty("user.home")
            val candidates = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "$home/Android/Sdk",
                "$home/Library/Android/sdk",
                System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
            )
            val name = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
            return candidates.map { File(it, "platform-tools/$name") }.firstOrNull { it.canExecute() }?.path ?: "adb"
        }
    }
}
