package dev.droidtop.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [content] as a QR code [size] across, made on the device (no network):
 * dark modules on white with a quiet border whatever the theme, because a
 * phone's scanner reads that and may not read the inverse. One module per
 * pixel, scaled up without smoothing. Used by Steam's sign-in and the
 * scraper key setup.
 */
@Composable
fun QrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    var bitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content) {
        bitmap = withContext(Dispatchers.Default) { qrBitmap(content) }
    }
    Box(
        modifier = modifier.size(size).background(Color.White).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current == null) {
            CircularProgressIndicator()
        } else {
            val painter = remember(current) { BitmapPainter(current.asImageBitmap(), filterQuality = FilterQuality.None) }
            Image(painter = painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        }
    }
}

private fun qrBitmap(content: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
