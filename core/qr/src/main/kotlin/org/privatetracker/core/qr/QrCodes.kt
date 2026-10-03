package org.privatetracker.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// Plain ZXing, without Android types, so both directions run in unit tests.

/** The modules of a QR code for [content], one entry per module, with a quiet zone of [margin] modules. */
fun encodeQr(content: String, margin: Int = 2): BitMatrix =
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            // M survives a little glare on a phone screen without making the code much denser.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

/**
 * Reads a QR code in a grayscale frame: the Y plane of a camera image, [rowStride] bytes per row.
 * Null when there is none, or it cannot be read yet; the next frame will try again.
 */
fun decodeQr(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
    val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), DECODE_HINTS).text
    } catch (e: ReaderException) {
        null
    }
}

private val DECODE_HINTS = mapOf(
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)
