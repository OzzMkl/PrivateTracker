package org.privatetracker.core.qr

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [content] as a QR code, black on white whatever the theme: scanners expect dark modules on a light
 * background. Drawn one pixel per module and scaled without smoothing, so edges stay sharp.
 */
@Composable
fun QrCode(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val painter = remember(content) {
        val matrix = encodeQr(content)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) BLACK else WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        BitmapPainter(bitmap.asImageBitmap(), filterQuality = FilterQuality.None)
    }
    Image(painter, contentDescription, modifier.background(Color.White))
}

/**
 * The back camera's preview, reading QR codes. [onScanned] gets each new text; return true to stop
 * scanning, as when the text is what the screen was waiting for. The camera permission must already
 * be granted.
 */
@Composable
fun QrScanner(onScanned: (String) -> Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned = rememberUpdatedState(onScanned)
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(context)
        var lastText: String? = null
        controller.setImageAnalysisAnalyzer(executor) { image ->
            val text = image.use { if (done.get()) null else it.decodeQr() }
            // The same unreadable or rejected code shows in frame after frame; report it once.
            if (text != null && text != lastText) {
                lastText = text
                main.execute { if (!done.get() && currentOnScanned.value(text)) done.set(true) }
            }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
        }
    }
    AndroidView(factory = { PreviewView(it).apply { this.controller = controller } }, modifier = modifier)
}

/** The Y plane of a camera frame is already the grayscale image ZXing reads. */
private fun ImageProxy.decodeQr(): String? {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return decodeQr(bytes, width, height, plane.rowStride)
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
