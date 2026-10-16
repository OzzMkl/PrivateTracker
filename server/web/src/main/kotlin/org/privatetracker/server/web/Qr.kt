package org.privatetracker.server.web

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR code of [text], with the phone app's error correction (M) and a quiet zone of [margin] modules. */
fun qrMatrix(text: String, margin: Int = 2): BitMatrix =
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to margin, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )

/**
 * [matrix] as SVG: one path, a run of dark modules per segment, black on white whatever the page's
 * theme, so phones scan it the same in dark mode. Presentation attributes only: the CSP allows no
 * inline styles.
 */
fun qrSvg(matrix: BitMatrix, label: String): String = buildString {
    append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" shape-rendering="crispEdges" role="img" aria-label="""")
    append(label.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"))
    append("""">""")
    append("""<rect width="${matrix.width}" height="${matrix.height}" fill="#fff"/><path fill="#000" d="""")
    for (y in 0 until matrix.height) {
        var x = 0
        while (x < matrix.width) {
            if (!matrix[x, y]) {
                x++
                continue
            }
            val start = x
            while (x < matrix.width && matrix[x, y]) x++
            append("M$start ${y}h${x - start}v1h-${x - start}z")
        }
    }
    append("\"/></svg>")
}
