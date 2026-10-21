package org.privatetracker.server.linux.keys

import org.privatetracker.core.common.time.Clock
import org.privatetracker.core.domain.port.ServerKeys
import org.privatetracker.server.hostkeys.IdentityKeyFile
import java.nio.file.Path
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The server's identity key on Linux, ECDSA P-256 like a phone server's Keystore key, in [path]. It is
 * made on first use and then never replaced: trackers pin it, so a file that no longer opens is an
 * error to fix by hand, not a reason to make a new identity. From 0.4 it is also the TLS key, and from
 * 1.0 the key the server shows a relay. Every method throws DeviceKeyException when the file cannot be
 * read or written.
 */
class FileServerKeys(path: Path, clock: Clock) : ServerKeys {
    private val file = IdentityKeyFile(path, ALIAS, "PrivateTracker Server", clock::now)

    override suspend fun publicKey(): String = file.publicKey()

    override suspend fun sign(data: ByteArray): ByteArray = file.sign(data)

    /** For the TLS server, and as the client certificate a relay checks: always this one key. */
    fun keyManager(): X509ExtendedKeyManager = file.keyManager()

    /** The self-signed certificate around the key: what the web port shows when the owner gave no other. */
    fun certificate(): X509Certificate = file.certificate()

    private companion object {
        const val ALIAS = "privatetracker-server"
    }
}
