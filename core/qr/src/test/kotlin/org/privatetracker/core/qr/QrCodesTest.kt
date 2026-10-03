package org.privatetracker.core.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodesTest {
    private val link = "privatetracker://pair?v=1&name=Casa&url=http%3A%2F%2F192.168.1.50%3A8787&key=" + "A".repeat(122) +
        "&ticket=tK3_x-9aBcDe&secret=AAECAwQFBgcICQoLDA0ODw&expires=1790000000"

    /** Renders [content] as a camera would see it: [scale] pixels per module, padded rows like a Y plane. */
    private fun frame(content: String, scale: Int, padding: Int): Triple<ByteArray, Int, Int> {
        val matrix = encodeQr(content)
        val width = matrix.width * scale
        val height = matrix.height * scale
        val stride = width + padding
        val bytes = ByteArray(stride * height) { 0x7F }
        for (y in 0 until height) for (x in 0 until width) {
            bytes[y * stride + x] = if (matrix[x / scale, y / scale]) 0 else 0xFF.toByte()
        }
        return Triple(bytes, width, stride)
    }

    @Test
    fun `a code the server draws is read back from a camera frame, row padding included`() {
        val (bytes, width, stride) = frame(link, scale = 4, padding = 32)

        assertEquals(link, decodeQr(bytes, width, bytes.size / stride, stride))
    }

    @Test
    fun `a frame without a code reads as nothing`() {
        assertNull(decodeQr(ByteArray(320 * 240) { 0x55 }, 320, 240))
    }
}
