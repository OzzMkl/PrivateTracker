package org.privatetracker.server.hostkeys

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

/** What only the process's own user may read: its directory (0700) and every file in it (0600). */
object OwnerOnly {
    val DIRECTORY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    val FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
}

/**
 * Creates [root] owner-only, or closes one that others could enter. Throws IOException, with a message
 * for the owner, when it cannot.
 */
fun prepareOwnerOnlyDirectory(root: Path) {
    if (!Files.isDirectory(root)) {
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(OwnerOnly.DIRECTORY))
    }
    if (Files.getPosixFilePermissions(root) != OwnerOnly.DIRECTORY) {
        try {
            Files.setPosixFilePermissions(root, OwnerOnly.DIRECTORY)
        } catch (e: FileSystemException) {
            throw IOException("otros usuarios pueden entrar y este usuario no puede impedirlo (chmod 700)", e)
        }
    }
}

/**
 * Takes [lockFile] for this process: an OS file lock, released when it closes or the process dies.
 * Null when another process holds it, even one that started at the same moment.
 */
fun lockFile(lockFile: Path): AutoCloseable? {
    val channel = FileChannel.open(lockFile, setOf(StandardOpenOption.CREATE, WRITE), PosixFilePermissions.asFileAttribute(OwnerOnly.FILE))
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
 * Replaces [target] with [bytes] so that a crash leaves the old file or the new one, never half of
 * either, and the new file is readable by its owner only (0600) from the moment it exists.
 */
fun writeAtomically(target: Path, bytes: ByteArray) {
    val directory = target.toAbsolutePath().parent
    val temp = Files.createTempFile(directory, ".${target.fileName}", ".tmp", PosixFilePermissions.asFileAttribute(OwnerOnly.FILE))
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
