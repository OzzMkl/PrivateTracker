package org.privatetracker.core.protocol

import org.privatetracker.core.protocol.crypto.Hpke
import org.privatetracker.core.protocol.crypto.P256
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RFC 9180, appendix A.3.1: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, base mode. */
class HpkeTest {
    private val info = hex("4f6465206f6e2061204772656369616e2055726e")
    private val skEm = hex("4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb")
    private val pkEm = hex(
        "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4",
    )
    private val skRm = hex("f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2")
    private val pkRm = hex(
        "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0",
    )
    private val plaintext = hex("4265617574792069732074727574682c20747275746820626561757479")

    /** Sequence number to aad and ciphertext, from A.3.1.1. */
    private val encryptions = mapOf(
        0L to ("436f756e742d30" to "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"),
        1L to ("436f756e742d31" to "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06646e0329cbdf3c3cd655b28e82"),
        2L to ("436f756e742d32" to "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b2ccc93e466c28fb55fed7a7fec"),
        4L to ("436f756e742d34" to "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec999dd36ce042e44f0c3169a6a8f"),
        255L to ("436f756e742d323535" to "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848aa53c538c30a4dac71d619ee2cd"),
        256L to ("436f756e742d323536" to "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f367f8ffe5e41cd3e9962a6afb2"),
    )

    /** From A.3.1.2: exporter context to exported value, 32 bytes each. */
    private val exports = listOf(
        "" to "5e9bc3d236e1911d95e65b576a8a86d478fb827e8bdfe77b741b289890490d4d",
        "00" to "6cff87658931bda83dc857e6353efe4987a201b849658d9b047aab4cf216e796",
        "54657374436f6e74657874" to "d8f1ea7942adbba7412c6d431c62d01371ea476b823eb697e1f6e6cae1dab85a",
    )

    private val recipientPublic: ECPublicKey = assertNotNull(P256.decodePoint(pkRm))

    private fun sender(): Hpke.Sender {
        val ephemeral = KeyPair(assertNotNull(P256.decodePoint(pkEm)), P256.privateKey(skEm))
        return Hpke.setupSender(recipientPublic, info, ephemeral)
    }

    private fun recipient(enc: ByteArray = pkEm): Hpke.Recipient? = Hpke.setupRecipient(enc, P256.privateKey(skRm), recipientPublic, info)

    @Test
    fun `the sender matches the RFC's encapsulated key, ciphertexts and exported values`() {
        val sender = sender()

        assertContentEquals(pkEm, sender.enc)
        for (sequence in 0L..256L) {
            val (aad, expected) = encryptions[sequence] ?: ("" to null)
            val ciphertext = sender.seal(hex(aad), plaintext)
            if (expected != null) assertEquals(expected, ciphertext.toHex(), "sequence $sequence")
        }
        for ((context, expected) in exports) assertEquals(expected, sender.export(hex(context), 32).toHex())
    }

    @Test
    fun `the recipient opens the RFC's ciphertexts and derives the same exported values`() {
        val recipient = assertNotNull(recipient())
        // The sequences between the RFC's come from a sender in step with the recipient.
        val sender = sender()

        for (sequence in 0L..256L) {
            val listed = encryptions[sequence]
            val aad = hex(listed?.first ?: "")
            val ciphertext = sender.seal(aad, plaintext).let { own -> listed?.second?.let(::hex) ?: own }
            assertContentEquals(plaintext, recipient.open(aad, ciphertext), "sequence $sequence")
        }
        for ((context, expected) in exports) assertEquals(expected, recipient.export(hex(context), 32).toHex())
    }

    @Test
    fun `a fresh key pair round-trips and anything altered fails to open`() {
        val pair = P256.generateKeyPair()
        val public = pair.public as ECPublicKey
        val sender = Hpke.setupSender(public, info)
        val sealed = sender.seal("aad".encodeToByteArray(), plaintext)

        val opened = Hpke.setupRecipient(sender.enc, pair.private, public, info)?.open("aad".encodeToByteArray(), sealed)
        assertContentEquals(plaintext, opened)

        val flipped = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(Hpke.setupRecipient(sender.enc, pair.private, public, info)?.open("aad".encodeToByteArray(), flipped))
        assertNull(Hpke.setupRecipient(sender.enc, pair.private, public, info)?.open("other".encodeToByteArray(), sealed))
        assertNull(Hpke.setupRecipient(sender.enc, pair.private, public, "other".encodeToByteArray())?.open("aad".encodeToByteArray(), sealed))
        val other = P256.generateKeyPair()
        assertNull(Hpke.setupRecipient(sender.enc, other.private, other.public as ECPublicKey, info)?.open("aad".encodeToByteArray(), sealed))
    }

    @Test
    fun `an encapsulated key off the curve is refused before any key agreement`() {
        val offCurve = pkEm.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertNull(recipient(offCurve))
        assertNull(recipient(pkEm.copyOf(64)))
        assertNull(recipient(byteArrayOf(0x02) + pkEm.copyOfRange(1, 33)))
        assertNull(recipient(ByteArray(65).also { it[0] = 0x04 }))
    }

    @Test
    fun `public keys travel as X_509 and only P-256 points come back`() {
        val public = P256.generateKeyPair().public as ECPublicKey
        val decoded = assertNotNull(P256.decodePublicKey(P256.encodePublicKey(public)))
        assertContentEquals(P256.encodePoint(public), P256.encodePoint(decoded))
        assertNull(P256.decodePublicKey("not base64!"))
        assertNull(P256.decodePublicKey("AAAA"))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
