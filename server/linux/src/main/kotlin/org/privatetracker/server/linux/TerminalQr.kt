package org.privatetracker.server.linux

import com.google.zxing.common.BitMatrix
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * [text] safe to print: control and formatting characters (escape sequences, right-to-left overrides)
 * become `?`. Device names come from the network, and a name must not redraw the owner's terminal.
 */
fun printable(text: String): String = buildString {
    text.codePoints().forEach { codePoint ->
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> append('?')
            else -> appendCodePoint(codePoint)
        }
    }
}

/**
 * [matrix] for a terminal: two rows of modules per line with half blocks, colored dark on light
 * explicitly, so it scans the same on a dark or a light terminal theme.
 */
fun terminalQr(matrix: BitMatrix): String = buildString {
    val dark = 30
    val light = 97
    for (y in 0 until matrix.height step 2) {
        for (x in 0 until matrix.width) {
            val top = if (matrix[x, y]) dark else light
            val bottom = if (y + 1 < matrix.height && matrix[x, y + 1]) dark else light
            // Foreground paints the upper half (▀), background the lower one.
            append("\u001b[").append(top).append(';').append(bottom + 10).append("m▀")
        }
        append("\u001b[0m\n")
    }
}

/** [matrix] as a PNG of [scale] pixels per module, for a QR to show on another screen. */
fun writeQrPng(matrix: BitMatrix, file: Path, scale: Int = 8) {
    val image = BufferedImage(matrix.width * scale, matrix.height * scale, BufferedImage.TYPE_BYTE_BINARY)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            image.setRGB(x, y, if (matrix[x / scale, y / scale]) 0x000000 else 0xffffff)
        }
    }
    ImageIO.write(image, "png", file.toFile())
}
